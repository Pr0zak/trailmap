package com.trailmap.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchTrails(
        center: GeoPoint,
        radiusMeters: Int,
        mtb: Boolean = false,
        forceRefresh: Boolean = false,
    ): List<Trail> =
        withContext(Dispatchers.IO) {
            if (mtb) {
                // Sequential (one Overpass request at a time) — firing both at once trips the
                // public server's per-IP rate limit (429). Parks are best-effort.
                val trailsRaw = cachedRaw("mtb", center, radiusMeters, forceRefresh) {
                    post(buildMtbQuery(center, radiusMeters))
                }
                val response = parseResponse(trailsRaw)
                val parks = runCatching {
                    val parksRaw = cachedRaw("parks", center, radiusMeters, forceRefresh) {
                        post(buildParkQuery(center, radiusMeters))
                    }
                    parseParks(parksRaw)
                }.getOrElse { emptyList() }
                buildMtbTrails(response.elements, center, parks)
            } else {
                val raw = cachedRaw("all", center, radiusMeters, forceRefresh) {
                    post(buildQuery(center, radiusMeters))
                }
                buildTrails(parseResponse(raw).elements, center)
            }
        }

    private fun parseResponse(raw: String): OverpassResponse =
        runCatching { json.decodeFromString<OverpassResponse>(raw) }
            .getOrElse { throw Exception("Overpass parse failed: ${it.message}", it) }

    // --- Disk cache ---------------------------------------------------------

    /**
     * Resolve the cache file for a given (kind, center, radius). Returns null when no
     * [cacheDir] is configured. Center is rounded to 3 decimals (~100 m) so small GPS
     * jitter reuses the same cache entry — fine for area-level trail discovery.
     */
    private fun cacheFile(kind: String, center: GeoPoint, radiusMeters: Int): File? {
        val dir = cacheDir ?: return null
        val key = "%s_%.3f_%.3f_%d".format(kind, center.lat, center.lon, radiusMeters)
        return File(File(dir, "overpass").apply { mkdirs() }, "$key.json")
    }

    /**
     * Cache-first wrapper around a network [fetch]:
     *  - fresh (< 7-day TTL) cache file & not [forceRefresh] → return it, no network.
     *  - otherwise fetch; on success write-through to the cache file (best-effort).
     *  - on fetch failure → fall back to any existing cache file (even if stale),
     *    so the app survives 429s / offline; if there's no cache, rethrow.
     */
    private fun cachedRaw(
        kind: String,
        center: GeoPoint,
        radiusMeters: Int,
        forceRefresh: Boolean,
        fetch: () -> String,
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
        } catch (e: Exception) {
            if (file != null && file.exists()) file.readText() // stale fallback
            else throw e
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

    /** Parse named park polygons from a raw Overpass response string. */
    private fun parseParks(raw: String): List<Park> {
        val response = json.decodeFromString<OverpassResponse>(raw)
        val parks = ArrayList<Park>()
        for (el in response.elements) {
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

    private fun post(query: String): String {
        val body = FormBody.Builder().add("data", query).build()
        var lastError: Exception? = null
        for (url in endpoints) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "trailmap-android/1.0 (+https://github.com/Pr0zak/trailmap)")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    return resp.body?.string() ?: throw IOException("empty response")
                }
            } catch (e: Exception) {
                lastError = e // rate-limited or unreachable → try the next mirror
            }
        }
        throw lastError ?: IOException("all Overpass endpoints failed")
    }

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

    private fun buildTrails(elements: List<OverpassElement>, center: GeoPoint): List<Trail> {
        val segments = elements.mapNotNull { el ->
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

        val trails = ArrayList<Trail>(named.size + unnamed.size)
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
        /** Disk-cache freshness window: 7 days. */
        val CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7)
        val BICYCLE_ALLOWED = setOf("yes", "designated", "permissive")
        val FOOT_ALLOWED = setOf("yes", "designated", "permissive")
        val WALK_HIGHWAYS = setOf("path", "footway", "track", "bridleway")
    }
}
