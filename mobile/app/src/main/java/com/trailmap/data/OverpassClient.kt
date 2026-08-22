package com.trailmap.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
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
class OverpassClient(private val cacheDir: File? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Overpass queries declare [timeout:120]; allow the read to match (the MTB park
        // query returns ~3 MB and can take >60 s under public-server load).
        .readTimeout(130, TimeUnit.SECONDS)
        // Bound the whole call too, so a body that trickles forever can't hold a mirror slot
        // past the point where the user has certainly moved on.
        .callTimeout(150, TimeUnit.SECONDS)
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

    /** After a 429, don't ask again until this time. */
    @Volatile
    private var rateLimitedUntil = 0L

    /** One-time prune of expired disk-cache entries; nothing else deletes from that dir. */
    @Volatile
    private var pruned = false

    /**
     * True when this (center, radius) can be served without touching the network — the caller
     * uses it to skip the ride-out-the-flick debounce for an area that will come back instantly.
     */
    fun isWarm(center: GeoPoint, radiusMeters: Int, mtb: Boolean): Boolean {
        val file = cacheFile(if (mtb) "mtb" else "all", center, radiusMeters) ?: return false
        synchronized(memo) { if (memo.containsKey(file.name)) return true }
        return file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS
    }

    suspend fun fetchTrails(
        center: GeoPoint,
        radiusMeters: Int,
        mtb: Boolean = false,
        forceRefresh: Boolean = false,
    ): List<Trail> =
        withContext(Dispatchers.IO) {
            lastServedStale = false
            pruneCacheOnce()
            if (mtb) {
                // Sequential (one Overpass request at a time) — firing both at once trips the
                // public server's per-IP rate limit (429). Parks are best-effort.
                val elements = elementsFor(
                    "mtb", center, radiusMeters, forceRefresh, buildMtbQuery(center, radiusMeters),
                )
                coroutineContext.ensureActive()
                val parks = runCatching { parksFor(center, radiusMeters, forceRefresh) }
                    .getOrElse { if (it is CancellationException) throw it else emptyList() }
                coroutineContext.ensureActive()
                buildMtbTrails(elements, center, parks)
            } else {
                val elements = elementsFor(
                    "all", center, radiusMeters, forceRefresh, buildQuery(center, radiusMeters),
                )
                coroutineContext.ensureActive()
                buildTrails(elements, center)
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
    private suspend fun elementsFor(
        kind: String,
        center: GeoPoint,
        radiusMeters: Int,
        forceRefresh: Boolean,
        query: String,
    ): List<OverpassElement> {
        val key = cacheFile(kind, center, radiusMeters)?.name
        if (!forceRefresh && key != null) {
            synchronized(memo) { memo[key] }?.let { return it.elements }
        }
        val raw = cachedRaw(kind, center, radiusMeters, forceRefresh) { post(query) }
        coroutineContext.ensureActive()
        val parsed = parseResponse(raw).elements
        if (key != null) memoPut(key, Memoized(parsed, raw.length))
        return parsed
    }

    /**
     * Named parks for the naming pass in MTB mode. Panning inside one metro re-uses the last
     * park set rather than pulling another multi-megabyte polygon response: park boundaries
     * don't change between two adjacent screens, and this halves MTB's per-pan cost.
     */
    private suspend fun parksFor(center: GeoPoint, radiusMeters: Int, forceRefresh: Boolean): List<Park> {
        val reusable = !forceRefresh && lastParksCenter != null &&
            lastParksRadius >= radiusMeters &&
            Geo.haversineMeters(lastParksCenter!!, center) < lastParksRadius * PARK_REUSE_FRACTION
        if (reusable) return lastParks

        val parks = parseParks(
            elementsFor("parks", center, radiusMeters, forceRefresh, buildParkQuery(center, radiusMeters)),
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
    private fun cacheFile(kind: String, center: GeoPoint, radiusMeters: Int): File? {
        val dir = cacheDir ?: return null
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
        fetch: suspend () -> String,
    ): String {
        val file = cacheFile(kind, center, radiusMeters)

        if (!forceRefresh && file != null && file.exists() &&
            System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS
        ) {
            return file.readText()
        }

        return try {
            val raw = fetch()
            if (file != null) runCatching { file.writeText(raw) } // best-effort write-through
            raw
        } catch (e: CancellationException) {
            throw e // a newer load superseded this one — don't mask it as a network failure
        } catch (e: Exception) {
            if (file != null && file.exists()) {
                lastServedStale = true // the UI says so rather than passing old data off as new
                file.readText()
            } else {
                throw e
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
              way["highway"~"^(path|cycleway|track|bridleway)$"](around:$r,$lat,$lon);
              way["highway"="footway"]["footway"!~"sidewalk|crossing|traffic_island|access_aisle"](around:$r,$lat,$lon);
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
              way["mtb:scale"](around:$r,$lat,$lon);
              way["highway"="path"]["bicycle"="designated"]["surface"~"ground|dirt|earth|fine_gravel|gravel|compacted"](around:$r,$lat,$lon);
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
        // Pan-triggered loading can ask for a lot; these two brakes are what keep a public,
        // shared, unauthenticated API from seeing a burst it would rightly 429.
        if (System.currentTimeMillis() < rateLimitedUntil) {
            throw IOException("Overpass rate-limited — using cached trails")
        }
        val sinceLast = System.currentTimeMillis() - lastNetworkAt
        if (sinceLast in 0 until MIN_NETWORK_GAP_MS) delay(MIN_NETWORK_GAP_MS - sinceLast)
        lastNetworkAt = System.currentTimeMillis()

        val body = FormBody.Builder().add("data", query).build()
        var lastError: Exception? = null
        for (url in endpoints) {
            coroutineContext.ensureActive()
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "trailmap-android/1.0 (+https://github.com/Pr0zak/trailmap)")
                    .post(body)
                    .build()
                return client.newCall(req).awaitBody()
            } catch (e: CancellationException) {
                throw e // the caller moved on; don't burn the remaining mirrors
            } catch (e: RateLimited) {
                rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                lastError = e // try the next mirror, but stop asking this one for a while
            } catch (e: Exception) {
                lastError = e // rate-limited or unreachable → try the next mirror
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
                    if (resp.code == 429 || resp.code == 504) {
                        cont.resumeWithException(RateLimited(resp.code))
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

    /** A mirror told us to back off (429), or buckled under load (504). */
    private class RateLimited(code: Int) : IOException("Overpass busy (HTTP $code)")

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

    private companion object {
        /** Bump when the Overpass queries change so old cached responses are ignored. */
        const val CACHE_SCHEMA = "v4"
        /** Disk-cache freshness window: 7 days. */
        val CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7)
        /** Total size of the JSON behind the in-memory parse cache, before LRU eviction. */
        const val MEMO_BUDGET_BYTES = 8L * 1024 * 1024
        /** Backstop on entry count, so many tiny zoomed-in areas can't accumulate forever. */
        const val MEMO_MAX_ENTRIES = 12
        /** Minimum gap between requests actually put on the wire. */
        const val MIN_NETWORK_GAP_MS = 1200L
        /** How long to stop asking after a mirror returns 429/504. */
        const val RATE_LIMIT_COOLDOWN_MS = 30_000L
        /** Reuse the last MTB park set while the map stays within this fraction of its radius. */
        const val PARK_REUSE_FRACTION = 0.5
        val BICYCLE_ALLOWED = setOf("yes", "designated", "permissive")
        val FOOT_ALLOWED = setOf("yes", "designated", "permissive")
        val WALK_HIGHWAYS = setOf("path", "footway", "track", "bridleway")
    }
}
