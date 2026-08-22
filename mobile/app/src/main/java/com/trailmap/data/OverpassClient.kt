package com.trailmap.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos

/**
 * Fetches OSM trail ways from the public Overpass API and groups them into
 * logical [Trail]s. The query deliberately excludes sidewalk/crossing footways
 * (which otherwise drown the result in ~25k urban-pavement ways).
 *
 * When [cacheDir] is non-null, raw Overpass responses are cached on disk under
 * `<cacheDir>/overpass`. Repeat loads of an area are served from disk (instant,
 * no network) and, if the network fails, a stale cached copy is returned.
 */
/**
 * Trails for an area, plus the circle the data actually covers — which is not always the one
 * that was asked for, since a cached pull can answer a nearby request.
 */
class TrailsResult(val trails: List<Trail>, val servedCenter: GeoPoint, val servedRadius: Int)

/**
 * @param cacheDir  transient responses, under the OS-evictable cache directory and swept by TTL.
 * @param durableDir where offline downloads go. A deliberate download has to still be there on a
 *   trail with no signal, so it must survive both the TTL sweep and Android reclaiming cache
 *   space — which the cache directory explicitly does not promise.
 */
class OverpassClient(
    private val cacheDir: File? = null,
    private val durableDir: File? = null,
    private val prefs: Prefs? = null,
) {

    /** Scope for background cache refreshes, which outlive the load that triggered them. */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Areas with a refresh already in flight, so a burst of pans queues only one. */
    private val refreshing = HashSet<String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Deliberately tight for a map that reloads as you pan. A healthy mirror answers a
        // named-way query in 2-20 s; a mirror that needs longer has effectively failed, and
        // waiting on it just holds the "Loading trails…" pill up while the user waits for a
        // screen they have often already panned away from. One mirror in the list has been
        // observed taking 40 s to return a 500.
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * True when the last response handed back was a stale cache fallback rather than a fresh
     * fetch. Read straight after [fetchTrails] so the UI can say so instead of presenting
     * week-old trails as current.
     */
    @Volatile
    var lastServedStale: Boolean = false
        private set

    /** Wall-clock of the last request actually put on the wire, for the minimum-gap throttle. */
    @Volatile
    private var lastNetworkAt = 0L

    /**
     * Per-endpoint cooldown after a genuine 429. Kept per mirror, never global: a single busy
     * server must not be able to stop the app from asking a healthy one.
     */
    private val cooldownUntil = HashMap<String, Long>()

    /** One-time prune of expired disk-cache entries; nothing else deletes from that dir. */
    @Volatile
    private var pruned = false

    /**
     * The mirror that answered last. Public Overpass instances go down, get overloaded, and
     * block individual IPs for a while, so once one has proved it works there is no reason to
     * keep paying a failed round-trip to a broken one ahead of it in the list.
     */
    @Volatile
    private var preferredEndpoint: String? = prefs?.preferredEndpoint()

    /**
     * Pull an area into the disk cache for offline use. Downloading an offline region used to
     * fetch basemap tiles only, so the map worked out of signal with no trails drawn on it.
     */
    suspend fun prefetch(center: GeoPoint, radiusMeters: Int, mtb: Boolean) {
        DiagLog.log("offline", "prefetch %.4f,%.4f r=%d".format(center.lat, center.lon, radiusMeters))
        fetchTrails(center, radiusMeters, mtb = mtb, forceRefresh = false, durable = true)
    }

    /** Bytes held by offline downloads, for the Offline screen to report. */
    fun durableBytes(): Long {
        val dir = durableDir?.let { File(it, "overpass") } ?: return 0L
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Delete every offline-downloaded area. */
    fun clearDurable() {
        durableDir?.let { File(it, "overpass") }?.listFiles()?.forEach { it.delete() }
    }

    /** True if this exact area is already on disk, so a prefetch can skip it. */
    fun hasArea(center: GeoPoint, radiusMeters: Int, mtb: Boolean): Boolean =
        coveringCache(if (mtb) "mtb" else "all", center, radiusMeters) != null

    /**
     * The circle that would answer this request from cache, or null if it needs the network.
     *
     * Lets the caller notice that a "refetch" would hand back the data it is already showing,
     * which is otherwise a livelock: the gate measures distance from the loaded circle's
     * centre, a cached circle keeps its own centre no matter who asks, so once the map is far
     * enough from that centre every camera idle refetches the same file forever.
     */
    fun cachedCircleFor(center: GeoPoint, radiusMeters: Int, mtb: Boolean): Pair<GeoPoint, Int>? =
        coveringCache(if (mtb) "mtb" else "all", center, radiusMeters)?.let { it.center to it.radius }

    /**
     * True when this (center, radius) can be served without touching the network — the caller
     * uses it to skip the ride-out-the-flick debounce for an area that will come back instantly.
     */
    fun isWarm(center: GeoPoint, radiusMeters: Int, mtb: Boolean): Boolean {
        val hit = coveringCache(if (mtb) "mtb" else "all", center, radiusMeters) ?: return false
        synchronized(memo) { if (memo.containsKey(hit.file.name)) return true }
        return System.currentTimeMillis() - hit.file.lastModified() < CACHE_TTL_MS
    }

    suspend fun fetchTrails(
        center: GeoPoint,
        radiusMeters: Int,
        mtb: Boolean = false,
        forceRefresh: Boolean = false,
        durable: Boolean = false,
    ): TrailsResult =
        withContext(Dispatchers.IO) {
            lastServedStale = false
            pruneCacheOnce()
            if (mtb) {
                // Sequential (one Overpass request at a time) — firing both at once trips the
                // public server's per-IP rate limit (429). Parks are best-effort.
                val e = elementsFor(
                    "mtb", center, radiusMeters, forceRefresh, buildMtbQuery(center, radiusMeters),
                    durable,
                )
                coroutineContext.ensureActive()
                val parks = runCatching { parksFor(center, radiusMeters, forceRefresh, durable) }
                    .getOrElse { if (it is CancellationException) throw it else emptyList() }
                coroutineContext.ensureActive()
                TrailsResult(buildMtbTrails(e.elements, center, parks), e.center, e.radius)
            } else {
                val e = elementsFor(
                    "all", center, radiusMeters, forceRefresh, buildQuery(center, radiusMeters),
                    durable,
                )
                coroutineContext.ensureActive()
                TrailsResult(buildTrails(e.elements, center), e.center, e.radius)
            }
        }

    private fun parseResponse(raw: String): OverpassResponse =
        runCatching { json.decodeFromString<OverpassResponse>(raw) }
            .getOrElse { throw Exception("Overpass parse failed: ${it.message}", it) }

    // --- In-memory parse cache ----------------------------------------------

    /** A parsed response plus the size of the JSON it came from, which the budget is charged in. */
    private class Memoized(val elements: List<OverpassElement>, val rawBytes: Int)

    /**
     * Recently parsed responses, keyed the same way as the disk cache. Deserializing a Kansas
     * City response is ~3 MB of JSON and dominates a disk-cache hit, so panning back to an
     * area you just left would otherwise re-pay it every time. Only the parsed elements are
     * kept, not the finished [Trail]s: [buildTrails] is cheap by comparison and has to re-run
     * anyway so that each trail's distance is measured from the *current* center.
     *
     * Access-ordered, and bounded by total response size rather than entry count — a 40-mile
     * MTB response is twenty times the size of a zoomed-in one, so counting entries would
     * either starve close-in panning or retain far too much.
     */
    private val memo = LinkedHashMap<String, Memoized>(8, 0.75f, true)
    private var memoBytes = 0L

    /** Insert under the byte budget, evicting least-recently-used entries to make room. */
    private fun memoPut(key: String, value: Memoized) = synchronized(memo) {
        memo.remove(key)?.let { memoBytes -= it.rawBytes }
        memo[key] = value
        memoBytes += value.rawBytes
        val it = memo.entries.iterator()
        while (it.hasNext() && (memoBytes > MEMO_BUDGET_BYTES || memo.size > MEMO_MAX_ENTRIES)) {
            val eldest = it.next()
            if (eldest.key == key) continue // never evict what we just inserted
            memoBytes -= eldest.value.rawBytes
            it.remove()
        }
    }

    /**
     * Parsed elements for one (kind, area), memo → disk → network in that order. A memo hit
     * skips the multi-megabyte file read as well as the parse.
     */
    private class Elements(
        val elements: List<OverpassElement>,
        val center: GeoPoint,
        val radius: Int,
    )

    private suspend fun elementsFor(
        kind: String,
        center: GeoPoint,
        radiusMeters: Int,
        forceRefresh: Boolean,
        query: String,
        durable: Boolean = false,
    ): Elements {
        // Work out which file would answer this before touching the disk, so a memo hit skips
        // the multi-megabyte read as well as the parse.
        val t0 = System.currentTimeMillis()
        val source = if (forceRefresh) null else coveringCache(kind, center, radiusMeters)
        if (source != null) {
            synchronized(memo) { memo[source.file.name] }?.let {
                DiagLog.log("cache", "$kind memory hit, covers ${source.radius} m (asked $radiusMeters)")
                return Elements(it.elements, source.center, source.radius)
            }
        }
        val raw = cachedRaw(kind, center, radiusMeters, forceRefresh, durable) { post(query) }
        coroutineContext.ensureActive()
        val parsed = parseResponse(raw.text).elements
        DiagLog.log(
            "cache",
            "$kind ${if (source != null) "disk hit" else "network"}, covers ${raw.radius} m " +
                "(asked $radiusMeters), ${raw.text.length / 1024} KB, ${parsed.size} elements, " +
                "${System.currentTimeMillis() - t0} ms",
        )
        val key = source?.file?.name ?: cacheFile(kind, center, raw.radius)?.name
        if (key != null) memoPut(key, Memoized(parsed, raw.text.length))
        return Elements(parsed, raw.center, raw.radius)
    }

    /**
     * Named parks for the naming pass in MTB mode. Panning inside one metro re-uses the last
     * park set rather than pulling another multi-megabyte polygon response: park boundaries
     * don't change between two adjacent screens, and this halves MTB's per-pan cost.
     */
    private suspend fun parksFor(
        center: GeoPoint,
        radiusMeters: Int,
        forceRefresh: Boolean,
        durable: Boolean,
    ): List<Park> {
        val reusable = !forceRefresh && lastParksCenter != null &&
            lastParksRadius >= radiusMeters &&
            Geo.haversineMeters(lastParksCenter!!, center) < lastParksRadius * PARK_REUSE_FRACTION
        if (reusable) return lastParks

        val parks = parseParks(
            elementsFor(
                "parks", center, radiusMeters, forceRefresh, buildParkQuery(center, radiusMeters),
                durable,
            ).elements,
        )
        lastParks = parks
        lastParksCenter = center
        lastParksRadius = radiusMeters
        return parks
    }

    private var lastParks: List<Park> = emptyList()
    private var lastParksCenter: GeoPoint? = null
    private var lastParksRadius = 0

    /** Delete expired cache files once per process. Pan-loading writes far more of them. */
    /** Sweeps the transient directory only; offline downloads are never pruned. */
    private fun pruneCacheOnce() {
        if (pruned) return
        pruned = true
        val dir = cacheDir?.let { File(it, "overpass") } ?: return
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        runCatching { dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() } }
    }

    // --- Disk cache ---------------------------------------------------------

    /**
     * Resolve the cache file for a given (kind, center, radius). Returns null when no
     * [cacheDir] is configured.
     */
    private fun cacheFile(
        kind: String,
        center: GeoPoint,
        radiusMeters: Int,
        durable: Boolean = false,
    ): File? {
        val dir = (if (durable) durableDir else cacheDir) ?: return null
        // Snap the center onto a grid sized at ~1/6 of the query radius. Panning the map
        // refetches around a new center every time, and a 100 m-precision key would mint a
        // fresh cache entry for each of those; a radius-relative grid means revisiting an
        // area you already pulled is a disk hit instead of a 3 MB download. Two centers that
        // share a cell differ by at most ~radius/4, well inside what the query covers.
        val stepMeters = (radiusMeters / 6.0).coerceAtLeast(250.0)
        val latStep = stepMeters / 111_320.0
        val lonStep = latStep / cos(Math.toRadians(center.lat)).coerceAtLeast(0.1)
        val qLat = Math.round(center.lat / latStep) * latStep
        val qLon = Math.round(center.lon / lonStep) * lonStep
        // CACHE_SCHEMA bumps whenever the queries (or this key format) change, invalidating
        // stale cached responses.
        val key = "%s_%s_%.5f_%.5f_%d".format(CACHE_SCHEMA, kind, qLat, qLon, radiusMeters)
        return File(File(dir, "overpass").apply { mkdirs() }, "$key.json")
    }

    /** A response already on disk, described by the circle it covers. */
    private class CachedCircle(val file: File, val center: GeoPoint, val radius: Int)

    /** Parse `v5_all_39.09450_-94.57901_8000.json` back into the circle it holds. */
    private fun parseCacheName(kind: String, f: File): CachedCircle? {
        val parts = f.name.removeSuffix(".json").split('_')
        if (parts.size != 5 || parts[0] != CACHE_SCHEMA || parts[1] != kind) return null
        val lat = parts[2].toDoubleOrNull() ?: return null
        val lon = parts[3].toDoubleOrNull() ?: return null
        val radius = parts[4].toIntOrNull() ?: return null
        return CachedCircle(f, GeoPoint(lat, lon), radius)
    }

    /**
     * The cached response that best covers the requested circle — any file whose own circle
     * fully contains it. Overpass `around:` returns everything inside the radius, so a wider
     * pull is a strict superset of a narrower one centred anywhere inside it.
     *
     * Without this the cache could only ever hit an exact centre-and-radius key, which meant
     * a downloaded area bought nothing: pan a few hundred metres, ask for a slightly different
     * centre, and it was a fresh download of data already on the device. The tightest cover
     * wins — least to parse, and closest to what was actually asked for.
     */
    private fun coveringCache(kind: String, center: GeoPoint, radiusMeters: Int): CachedCircle? {
        val dirs = listOfNotNull(
            durableDir?.let { File(it, "overpass") },
            cacheDir?.let { File(it, "overpass") },
        )
        var best: CachedCircle? = null
        for (f in dirs.flatMap { it.listFiles()?.asList() ?: emptyList() }) {
            val c = parseCacheName(kind, f) ?: continue
            if (c.radius < radiusMeters) continue
            // Strict containment is too strict for the common case. A cached circle of the
            // *same* radius would only ever match a request at exactly its centre, so a warm
            // restart a few hundred metres away went to the network for data already on disk.
            // Allow the request to poke out by a fraction of the radius: the caller records
            // the circle it was actually served, so the coverage gate stays honest and simply
            // refetches once the map really does leave the area.
            if (Geo.haversineMeters(c.center, center) + radiusMeters > c.radius * (1 + COVER_SLACK)) continue
            val b = best
            if (b == null || c.radius < b.radius) best = c
        }
        return best
    }

    /** A raw response plus the radius it actually covers (which may exceed what was asked). */
    private class Raw(val text: String, val center: GeoPoint, val radius: Int)

    /**
     * Cache-first wrapper around a network [fetch]:
     *  - fresh (< 7-day TTL) cache file & not [forceRefresh] → return it, no network.
     *  - otherwise fetch; on success write-through to the cache file (best-effort).
     *  - on fetch failure → fall back to any existing cache file (even if stale),
     *    so the app survives 429s / offline; if there's no cache, rethrow.
     */
    private suspend fun cachedRaw(
        kind: String,
        center: GeoPoint,
        radiusMeters: Int,
        forceRefresh: Boolean,
        durable: Boolean,
        fetch: suspend () -> String,
    ): Raw {
        val file = cacheFile(kind, center, radiusMeters, durable)
        val covering = if (forceRefresh) null else coveringCache(kind, center, radiusMeters)

        if (covering != null) {
            // Serve what we have straight away, expired or not, and refresh behind the scenes
            // if it is past the TTL. Trail geometry barely changes week to week, and making
            // the user watch a spinner for data already on the device is the whole complaint.
            if (System.currentTimeMillis() - covering.file.lastModified() >= CACHE_TTL_MS) {
                scheduleRefresh(kind, center, radiusMeters, fetch)
            }
            return Raw(covering.file.readText(), covering.center, covering.radius)
        }

        return try {
            val raw = fetch()
            if (file != null) runCatching { file.writeText(raw) } // best-effort write-through
            Raw(raw, center, radiusMeters)
        } catch (e: CancellationException) {
            throw e // a newer load superseded this one — don't mask it as a network failure
        } catch (e: Exception) {
            // Offline, or blocked: an expired copy that covers the area still beats a blank map.
            val stale = covering
                ?: file?.takeIf { it.exists() }?.let { CachedCircle(it, center, radiusMeters) }
            if (stale != null) {
                DiagLog.log("cache", "$kind fetch failed (${e.message}), serving stale ${stale.radius} m copy")
                lastServedStale = true // the UI says so rather than passing old data off as new
                Raw(stale.file.readText(), stale.center, stale.radius)
            } else {
                throw e
            }
        }
    }

    /**
     * ALL-mode query. Note the `["name"]` on both clauses: [TrailsUiState.filtered] drops every
     * trail called "Unnamed path" before anything is drawn or listed, so unnamed ways were
     * being downloaded, parsed and held in memory only to be discarded. Asking the server to
     * filter instead takes a Kansas City 8 km pull from 4,034 ways / 3.13 MB down to 277 ways
     * / 416 KB with no visible difference. If unnamed connectors are ever wanted on the map,
     * this filter and that one have to come off together.
     */
    /** Re-fetch an expired area in the background and write it through to the cache. */
    private fun scheduleRefresh(
        kind: String,
        center: GeoPoint,
        radiusMeters: Int,
        fetch: suspend () -> String,
    ) {
        val file = cacheFile(kind, center, radiusMeters) ?: return
        synchronized(refreshing) { if (!refreshing.add(file.name)) return }
        refreshScope.launch {
            try {
                val raw = fetch()
                runCatching { file.writeText(raw) }
            } catch (_: Exception) {
                // Offline or blocked; the stale copy we already served stands.
            } finally {
                synchronized(refreshing) { refreshing.remove(file.name) }
            }
        }
    }

    private fun buildQuery(center: GeoPoint, radiusMeters: Int): String {
        val r = radiusMeters
        val lat = center.lat
        val lon = center.lon
        return """
            [out:json][timeout:90];
            (
              way["highway"~"^(path|cycleway|track|bridleway)$"]["name"](around:$r,$lat,$lon);
              way["highway"="footway"]["footway"!~"sidewalk|crossing|traffic_island|access_aisle"]["name"](around:$r,$lat,$lon);
            );
            out geom;
        """.trimIndent()
    }

    private fun buildMtbQuery(center: GeoPoint, radiusMeters: Int): String {
        val r = radiusMeters
        val lat = center.lat
        val lon = center.lon
        return """
            [out:json][timeout:180];
            (
              way["mtb:scale"]["name"](around:$r,$lat,$lon);
              way["highway"="path"]["bicycle"="designated"]["surface"~"ground|dirt|earth|fine_gravel|gravel|compacted"]["name"](around:$r,$lat,$lon);
              relation["route"="mtb"](around:$r,$lat,$lon);
            );
            out geom;
        """.trimIndent()
    }

    /** Overpass query for named green-space areas (parks, reserves, protected areas) in the same bbox. */
    private fun buildParkQuery(center: GeoPoint, radiusMeters: Int): String {
        val r = radiusMeters
        val lat = center.lat
        val lon = center.lon
        return """
            [out:json][timeout:120];
            (
              way["leisure"~"^(park|nature_reserve|recreation_ground)$"]["name"](around:$r,$lat,$lon);
              way["boundary"~"^(protected_area|national_park)$"]["name"](around:$r,$lat,$lon);
              relation["leisure"~"^(park|nature_reserve|recreation_ground)$"]["name"](around:$r,$lat,$lon);
              relation["boundary"~"^(protected_area|national_park)$"]["name"](around:$r,$lat,$lon);
            );
            out geom;
        """.trimIndent()
    }

    /** Build named park polygons from already-parsed Overpass elements. */
    private fun parseParks(elements: List<OverpassElement>): List<Park> {
        val parks = ArrayList<Park>()
        for (el in elements) {
            val name = el.tags["name"]?.trim()?.ifBlank { null } ?: continue
            val rings = ArrayList<List<GeoPoint>>()
            when (el.type) {
                "way" -> {
                    val ring = el.geometry.map { GeoPoint(it.lat, it.lon) }
                    if (ring.size >= 3) rings.add(ring)
                }
                "relation" -> {
                    val ways = el.members.filter { it.type == "way" }
                    val hasRoles = ways.any { it.role.isNotBlank() }
                    for (m in ways) {
                        if (hasRoles && m.role != "outer") continue
                        val ring = m.geometry.map { GeoPoint(it.lat, it.lon) }
                        if (ring.size >= 3) rings.add(ring)
                    }
                }
            }
            if (rings.isNotEmpty()) parks.add(Park(name, rings))
        }
        return parks
    }

    /** A named green-space area: name + one or more outer rings (multipolygon, best-effort). */
    private data class Park(val name: String, val rings: List<List<GeoPoint>>) {
        /** bbox area in (lat×lon) degrees² — a cheap proxy for "smallest / most specific" park. */
        val bboxArea: Double by lazy {
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            for (ring in rings) for (p in ring) {
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
                if (p.lon < minLon) minLon = p.lon
                if (p.lon > maxLon) maxLon = p.lon
            }
            (maxLat - minLat) * (maxLon - minLon)
        }
    }

    /** Standard ray-casting point-in-polygon test on lat/lon. */
    private fun pointInRing(p: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i].lat; val xi = ring[i].lon
            val yj = ring[j].lat; val xj = ring[j].lon
            val intersects = ((yi > p.lat) != (yj > p.lat)) &&
                (p.lon < (xj - xi) * (p.lat - yi) / (yj - yi) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /** Name of the smallest-bbox (most specific) park whose any ring contains [point]; null if none. */
    private fun parkNameFor(point: GeoPoint, parks: List<Park>): String? =
        parks.filter { park -> park.rings.any { pointInRing(point, it) } }
            .minByOrNull { it.bboxArea }?.name

    /** Public Overpass endpoints, tried in order — falls past a rate-limited (429) or down mirror. */
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private suspend fun post(query: String): String {
        // Pan-triggered loading can ask for a lot, so keep a floor on how often anything
        // actually goes on the wire. This throttles; it never refuses.
        val sinceLast = System.currentTimeMillis() - lastNetworkAt
        if (sinceLast in 0 until MIN_NETWORK_GAP_MS) delay(MIN_NETWORK_GAP_MS - sinceLast)
        lastNetworkAt = System.currentTimeMillis()

        // Prefer mirrors that aren't cooling down, but if every one of them is, try them all
        // anyway — a mirror that 429'd a while ago is still a better bet than no trails.
        val now = System.currentTimeMillis()
        val fresh = synchronized(cooldownUntil) { endpoints.filter { (cooldownUntil[it] ?: 0L) <= now } }
        val order = fresh.ifEmpty { endpoints }
            .sortedByDescending { it == preferredEndpoint }

        val body = FormBody.Builder().add("data", query).build()
        var lastError: Exception? = null
        for (url in order) {
            coroutineContext.ensureActive()
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "trailmap-android/1.0 (+https://github.com/Pr0zak/trailmap)")
                    .post(body)
                    .build()
                val t = System.currentTimeMillis()
                return client.newCall(req).awaitBody().also {
                    if (preferredEndpoint != url) {
                        preferredEndpoint = url
                        prefs?.setPreferredEndpoint(url)
                    }
                    DiagLog.log(
                        "http",
                        "${java.net.URI(url).host} OK ${it.length / 1024} KB in " +
                            "${System.currentTimeMillis() - t} ms",
                    )
                }
            } catch (e: CancellationException) {
                throw e // the caller moved on; don't burn the remaining mirrors
            } catch (e: RateLimited) {
                synchronized(cooldownUntil) {
                    cooldownUntil[url] = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                }
                DiagLog.log("http", "${java.net.URI(url).host} rate-limited, cooling down 30 s")
                lastError = e // move on, and stop asking *this* mirror for a while
            } catch (e: Exception) {
                DiagLog.log("http", "${java.net.URI(url).host} failed: ${e.javaClass.simpleName} ${e.message}")
                lastError = e // unreachable or erroring → try the next mirror
            }
        }
        throw lastError ?: IOException("all Overpass endpoints failed")
    }

    /**
     * Await an OkHttp call as a suspend function, cancelling the in-flight HTTP request when
     * the coroutine is cancelled. Panning the map supersedes loads constantly, and a blocking
     * `execute()` would keep downloading a multi-megabyte response nobody is going to read.
     */
    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (cont.isCancelled) return
                    // Only 429 means "you are asking too often". A 502/504 from Overpass is
                    // the dispatcher reporting it is momentarily too busy — transient, and
                    // the right response is the next mirror, not a cooldown.
                    if (resp.code == 429) {
                        cont.resumeWithException(RateLimited(429))
                        return
                    }
                    if (!resp.isSuccessful) {
                        cont.resumeWithException(IOException("HTTP ${resp.code}"))
                        return
                    }
                    val text = runCatching { resp.body?.string() }.getOrNull()
                    if (text == null) cont.resumeWithException(IOException("empty response"))
                    else cont.resume(text)
                }
            }
        })
    }

    /** A mirror explicitly told us to back off. */
    private class RateLimited(code: Int) : IOException("Overpass rate limit (HTTP $code)")

    // --- DTOs ---

    @Serializable
    private data class OverpassResponse(val elements: List<OverpassElement> = emptyList())

    @Serializable
    private data class OverpassElement(
        val type: String = "way",
        val id: Long = 0L,
        val tags: Map<String, String> = emptyMap(),
        val geometry: List<OverpassNode> = emptyList(),
        val members: List<OverpassMember> = emptyList(),
    )

    @Serializable
    private data class OverpassMember(
        val type: String = "way",
        val ref: Long = 0L,
        val role: String = "",
        val geometry: List<OverpassNode> = emptyList(),
    )

    @Serializable
    private data class OverpassNode(val lat: Double, val lon: Double)

    /** A single parsed way ready for grouping. */
    private data class WaySegment(
        val id: Long,
        val tags: Map<String, String>,
        val points: List<GeoPoint>,
        val surface: SurfaceType,
        val uses: Set<UseType>,
        val length: Double,
        val mtbScale: Int? = null,
    )

    /**
     * ALL-mode parsing: response contains both `way` and `relation` elements.
     * Relations (route=bicycle — named bike routes/parkways) each become one trail;
     * standalone ways are grouped by name. Ways already referenced by a relation are
     * skipped to avoid double-listing a route's member ways.
     */
    private fun buildTrails(elements: List<OverpassElement>, center: GeoPoint): List<Trail> {
        val ways = elements.filter { it.type == "way" }
        val relations = elements.filter { it.type == "relation" }

        // Way ids already represented by a relation member — skip them as standalone.
        val relationWayRefs = HashSet<Long>()
        for (rel in relations) {
            for (m in rel.members) if (m.type == "way") relationWayRefs.add(m.ref)
        }

        val trails = ArrayList<Trail>()

        // --- Relations: one logical trail each (route=bicycle, skip nameless) ---
        for (rel in relations) {
            val name = trailName(rel.tags["name"]) ?: continue
            val memberPaths = rel.members
                .filter { it.type == "way" }
                .map { m -> m.geometry.map { GeoPoint(it.lat, it.lon) } }
                .filter { it.size >= 2 }
            if (memberPaths.isEmpty()) continue

            // Urban bikeways/parkways are typically paved; fall back to PAVED when untagged.
            val relSurface = SurfaceType.fromOsmSurface(rel.tags["surface"])
                .takeIf { it != SurfaceType.UNKNOWN } ?: SurfaceType.PAVED
            val relUses = if (rel.tags["foot"] == "no") setOf(UseType.BIKE)
            else setOf(UseType.BIKE, UseType.WALK)

            val relMembers = memberPaths.map { pts ->
                WaySegment(
                    id = 0L,
                    tags = emptyMap(),
                    points = pts,
                    surface = relSurface,
                    uses = relUses,
                    length = Geo.lengthMeters(pts),
                    mtbScale = null,
                )
            }
            trails.add(
                assemble(
                    id = "rel_${rel.id}",
                    name = name,
                    members = relMembers,
                    center = center,
                    mtbScale = null,
                )
            )
        }

        // --- Standalone ways: same parsing as before (skip relation members) ---
        val segments = ways.mapNotNull { el ->
            if (el.id in relationWayRefs) return@mapNotNull null
            val points = el.geometry.map { GeoPoint(it.lat, it.lon) }
            if (points.size < 2) return@mapNotNull null
            WaySegment(
                id = el.id,
                tags = el.tags,
                points = points,
                surface = segmentSurface(el.tags),
                uses = segmentUses(el.tags),
                length = Geo.lengthMeters(points),
            )
        }

        // Group named ways together; unnamed ways each stand alone.
        val named = LinkedHashMap<String, MutableList<WaySegment>>()
        val unnamed = ArrayList<WaySegment>()
        for (seg in segments) {
            val name = trailName(seg.tags["name"])
            if (name == null) unnamed.add(seg)
            else named.getOrPut(name) { ArrayList() }.add(seg)
        }

        for ((name, members) in named) {
            trails.add(assemble(id = "name_" + slug(name), name = name, members = members, center = center))
        }
        for (seg in unnamed) {
            trails.add(assemble(id = "way_${seg.id}", name = "Unnamed path", members = listOf(seg), center = center))
        }

        // Drop trivially short unnamed connectors (driveway stubs, plaza links) to cut noise;
        // always keep named trails regardless of length.
        return trails
            .filter { it.name != "Unnamed path" || it.lengthMeters >= 80.0 }
            .sortedBy { it.distanceMeters }
    }

    /**
     * MTB-mode parsing: response contains both `way` and `relation` elements.
     * Relations (route=mtb) each become one trail; standalone ways are grouped by name.
     * Ways already referenced by a relation are skipped to avoid double-listing.
     */
    private fun buildMtbTrails(
        elements: List<OverpassElement>,
        center: GeoPoint,
        parks: List<Park> = emptyList(),
    ): List<Trail> {
        val ways = elements.filter { it.type == "way" }
        val relations = elements.filter { it.type == "relation" }

        // Way ids already represented by a relation member — skip them as standalone.
        val relationWayRefs = HashSet<Long>()
        for (rel in relations) {
            for (m in rel.members) if (m.type == "way") relationWayRefs.add(m.ref)
        }

        val trails = ArrayList<Trail>()

        // --- Relations: one logical trail each (skip nameless) ---
        for (rel in relations) {
            val name = trailName(rel.tags["name"]) ?: continue
            val memberPaths = rel.members
                .filter { it.type == "way" }
                .map { m -> m.geometry.map { GeoPoint(it.lat, it.lon) } }
                .filter { it.size >= 2 }
            if (memberPaths.isEmpty()) continue

            val relSurface = SurfaceType.fromOsmSurface(rel.tags["surface"])
                .takeIf { it != SurfaceType.UNKNOWN } ?: SurfaceType.DIRT
            val relScale = parseMtbScale(rel.tags["mtb:scale"])

            val relMembers = memberPaths.map { pts ->
                WaySegment(
                    id = 0L,
                    tags = emptyMap(),
                    points = pts,
                    surface = relSurface,
                    uses = setOf(UseType.BIKE, UseType.WALK),
                    length = Geo.lengthMeters(pts),
                    mtbScale = relScale,
                )
            }
            trails.add(
                assemble(
                    id = "rel_${rel.id}",
                    name = name,
                    members = relMembers,
                    center = center,
                    mtbScale = relScale,
                    parks = parks,
                )
            )
        }

        // --- Standalone ways: same parsing as ALL mode, plus mtb:scale + bike uses ---
        val segments = ways.mapNotNull { el ->
            if (el.id in relationWayRefs) return@mapNotNull null
            val points = el.geometry.map { GeoPoint(it.lat, it.lon) }
            if (points.size < 2) return@mapNotNull null
            WaySegment(
                id = el.id,
                tags = el.tags,
                points = points,
                surface = segmentSurface(el.tags),
                uses = mtbSegmentUses(el.tags),
                length = Geo.lengthMeters(points),
                mtbScale = parseMtbScale(el.tags["mtb:scale"]),
            )
        }

        val named = LinkedHashMap<String, MutableList<WaySegment>>()
        val unnamed = ArrayList<WaySegment>()
        for (seg in segments) {
            val name = trailName(seg.tags["name"])
            if (name == null) unnamed.add(seg)
            else named.getOrPut(name) { ArrayList() }.add(seg)
        }
        for ((name, members) in named) {
            trails.add(assemble(id = "name_" + slug(name), name = name, members = members, center = center, parks = parks))
        }
        for (seg in unnamed) {
            trails.add(assemble(id = "way_${seg.id}", name = "Unnamed path", members = listOf(seg), center = center, parks = parks))
        }

        return trails
            .filter { it.name != "Unnamed path" || it.lengthMeters >= 80.0 }
            .sortedBy { it.distanceMeters }
    }

    /** MTB ways are bike trails: always BIKE, plus WALK unless foot=no. */
    private fun mtbSegmentUses(tags: Map<String, String>): Set<UseType> {
        val uses = LinkedHashSet<UseType>()
        uses.add(UseType.BIKE)
        if (tags["foot"] != "no") uses.add(UseType.WALK)
        return uses
    }

    /** Parse the leading integer of an mtb:scale value (e.g. "2", "1+", "S2"), clamped 0..6; null if none. */
    private fun parseMtbScale(raw: String?): Int? {
        if (raw == null) return null
        val digit = raw.firstOrNull { it.isDigit() } ?: return null
        val v = digit - '0'
        return if (v in 0..6) v else null
    }

    /** Dominant surface bucket, surface mix, uses, geometry + nearest-distance for a group of ways. */
    private fun assemble(
        id: String,
        name: String,
        members: List<WaySegment>,
        center: GeoPoint,
        mtbScale: Int? = members.mapNotNull { it.mtbScale }.maxOrNull(),
        parks: List<Park> = emptyList(),
    ): Trail {
        val paths = members.map { it.points }
        val allPoints = members.flatMap { it.points }
        val totalLength = members.sumOf { it.length }

        // length-weighted surface fractions
        val bySurface = HashMap<SurfaceType, Double>()
        for (m in members) bySurface[m.surface] = (bySurface[m.surface] ?: 0.0) + m.length
        val surfaceMix: Map<SurfaceType, Double> =
            if (totalLength > 0.0) bySurface.mapValues { it.value / totalLength }
            else bySurface.mapValues { 0.0 }
        val dominant = bySurface.maxByOrNull { it.value }?.key ?: SurfaceType.UNKNOWN

        val uses = members.flatMap { it.uses }.toSet().ifEmpty { setOf(UseType.WALK) }

        val distance = Geo.minDistanceToVertices(allPoints, center)

        // bbox midpoint
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in allPoints) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        val mid = GeoPoint((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)

        // Attribute the trail to the most-specific named park containing its bbox midpoint.
        val parkName = if (parks.isEmpty()) null else parkNameFor(mid, parks)

        return Trail(
            id = id,
            name = name,
            surface = dominant,
            surfaceMix = surfaceMix,
            uses = uses,
            lengthMeters = totalLength,
            distanceMeters = distance,
            paths = paths,
            center = mid,
            mtbScale = mtbScale,
            parkName = parkName,
        )
    }

    private fun segmentSurface(tags: Map<String, String>): SurfaceType {
        val fromSurface = SurfaceType.fromOsmSurface(tags["surface"])
        return if (fromSurface != SurfaceType.UNKNOWN) fromSurface
        else SurfaceType.inferFromHighway(tags["highway"])
    }

    private fun segmentUses(tags: Map<String, String>): Set<UseType> {
        val highway = tags["highway"]
        val bicycle = tags["bicycle"]
        val foot = tags["foot"]
        val uses = LinkedHashSet<UseType>()

        if (highway == "cycleway" || bicycle in BICYCLE_ALLOWED) uses.add(UseType.BIKE)

        val walkByType = highway in WALK_HIGHWAYS
        val walkByTag = foot in FOOT_ALLOWED
        if (foot != "no" && (walkByType || walkByTag)) uses.add(UseType.WALK)
        // cycleway allows walking unless foot=no
        if (highway == "cycleway" && foot != "no") uses.add(UseType.WALK)

        if (uses.isEmpty()) uses.add(UseType.WALK)
        return uses
    }

    /** name=* with multi-values split on ';' (take the first); null/blank => unnamed. */
    private fun trailName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val first = raw.substringBefore(';').trim()
        return first.ifBlank { null }
    }

    private fun slug(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "trail" }

    companion object {
        /** Bump when the Overpass queries change so old cached responses are ignored. */
        const val CACHE_SCHEMA = "v5"
        /** Disk-cache freshness window: 7 days. */
        val CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7)
        /** Total size of the JSON behind the in-memory parse cache, before LRU eviction. */
        const val MEMO_BUDGET_BYTES = 8L * 1024 * 1024
        /** Backstop on entry count, so many tiny zoomed-in areas can't accumulate forever. */
        const val MEMO_MAX_ENTRIES = 12
        /**
         * Minimum gap between requests actually put on the wire. Overpass instances hand out
         * temporary per-IP blocks, and a map that refetches as you pan is the exact traffic
         * shape that earns one — this is the hard floor on how fast that can happen.
         */
        const val MIN_NETWORK_GAP_MS = 3000L
        /** How long to stop asking after a mirror returns 429/504. */
        const val RATE_LIMIT_COOLDOWN_MS = 30_000L
        /**
         * How far a request may poke outside a cached circle and still be served by it.
         * Public because the offline prefetch sizes its tiles from this rule.
         */
        const val COVER_SLACK = 0.15

        /** Reuse the last MTB park set while the map stays within this fraction of its radius. */
        const val PARK_REUSE_FRACTION = 0.5
        val BICYCLE_ALLOWED = setOf("yes", "designated", "permissive")
        val FOOT_ALLOWED = setOf("yes", "designated", "permissive")
        val WALK_HIGHWAYS = setOf("path", "footway", "track", "bridleway")
    }
}
