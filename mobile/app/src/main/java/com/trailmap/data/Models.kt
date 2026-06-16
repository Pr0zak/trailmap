package com.trailmap.data

import androidx.compose.ui.graphics.Color

/** A single lat/lon vertex. Domain type kept independent of MapLibre's LatLng. */
data class GeoPoint(val lat: Double, val lon: Double)

/** Surface buckets we render + filter by. Raw OSM surface=* values map into these. */
enum class SurfaceType(val label: String, val color: Color) {
    PAVED("Paved", Color(0xFF2E7D4F)),   // green
    GRAVEL("Gravel", Color(0xFFDAA520)), // goldenrod — distinct gold/yellow
    DIRT("Dirt", Color(0xFFA0522D)),     // sienna — distinct red-brown
    UNKNOWN("Unknown", Color(0xFF7A7A7A));

    companion object {
        /** Map a raw OSM surface tag to a bucket. */
        fun fromOsmSurface(surface: String?): SurfaceType = when (surface?.lowercase()) {
            "asphalt", "concrete", "paved", "paving_stones", "concrete:plates",
            "concrete:lanes", "brick", "bricks", "metal", "wood" -> PAVED
            "gravel", "fine_gravel", "compacted", "unpaved", "pebblestone" -> GRAVEL
            "dirt", "ground", "earth", "mud", "sand", "grass", "soil", "woodchips" -> DIRT
            else -> UNKNOWN
        }

        /** Fallback surface guess from the highway type when surface=* is missing. */
        fun inferFromHighway(highway: String?): SurfaceType = when (highway) {
            "cycleway" -> PAVED      // paved bike paths dominate
            "track" -> GRAVEL        // farm/forest tracks
            "path", "bridleway" -> DIRT
            else -> UNKNOWN
        }
    }
}

/** Intended use, derived from OSM bicycle/foot designation + highway type. */
enum class UseType(val label: String) { WALK("Walking"), BIKE("Biking") }

/** OSM mtb:scale (0..6) mapped to IMBA-ish difficulty labels + colors for MTB mode. */
enum class MtbDifficulty(val scale: Int, val label: String, val color: Color) {
    S0(0, "Beginner", Color(0xFF43A047)),
    S1(1, "Easy", Color(0xFF1E9E6A)),
    S2(2, "Intermediate", Color(0xFF1E88E5)),
    S3(3, "Advanced", Color(0xFF424242)),
    S4(4, "Expert", Color(0xFFE53935)),
    S5(5, "Expert+", Color(0xFFB71C1C)),
    S6(6, "Extreme", Color(0xFF7F0000));

    /** Short chip text, e.g. "S2 · Intermediate". */
    val badge: String get() = "S$scale · $label"

    companion object {
        fun of(scale: Int?): MtbDifficulty? = scale?.let { s -> entries.firstOrNull { it.scale == s } }
    }
}

/**
 * A logical trail: many OSM way-segments grouped by name and stitched together.
 * [paths] holds each contiguous polyline (a named trail can have disjoint pieces).
 */
data class Trail(
    val id: String,                 // stable id (name slug, or way id for unnamed)
    val name: String,               // display name ("Unnamed path" for nameless)
    val surface: SurfaceType,       // dominant surface across segments
    val surfaceMix: Map<SurfaceType, Double>, // fraction of length per surface
    val uses: Set<UseType>,         // walk / bike allowance
    val lengthMeters: Double,       // summed segment length
    val distanceMeters: Double,     // nearest point → user, meters
    val paths: List<List<GeoPoint>>,// polylines for map rendering
    val center: GeoPoint,           // centroid-ish, for list/thumbnail
    val mtbScale: Int? = null,      // OSM mtb:scale (0..6) if this is an MTB-rated trail
    val parkName: String? = null,   // name of the OSM park/area containing the trail (MTB mode)
) {
    val lengthMiles: Double get() = lengthMeters / 1609.344
    val distanceMiles: Double get() = distanceMeters / 1609.344
    /** Flattened vertices, for elevation sampling + bbox. */
    val allPoints: List<GeoPoint> get() = paths.flatten()
}

/**
 * A cluster of nearby [Trail]s — e.g. a park's trail network, grouped for MTB mode.
 * Built by [clusterTrailSystems] from trail center proximity.
 */
data class TrailSystem(
    val id: String,
    val name: String,
    val trails: List<Trail>,      // members, sorted by distance
    val totalMeters: Double,
    val distanceMeters: Double,   // nearest member
    val scaleMin: Int?,           // min mtb:scale across members (null if none rated)
    val scaleMax: Int?,
    val center: GeoPoint,
) {
    val totalMiles: Double get() = totalMeters / 1609.344
    val distanceMiles: Double get() = distanceMeters / 1609.344
}

/**
 * Group [trails] into [TrailSystem]s by center-point proximity using union-find:
 * two trails join the same system if their centers are within [thresholdMeters],
 * and the relation is transitive (A~B, B~C ⇒ A,B,C share a system).
 *
 * Naming: the word-level longest common prefix of member names; if blank/empty, the
 * name of the longest member. Systems are returned sorted by nearest-member distance.
 */
fun clusterTrailSystems(trails: List<Trail>, thresholdMeters: Double = 2500.0): List<TrailSystem> {
    val n = trails.size
    if (n == 0) return emptyList()

    // Union-find over trail indices.
    val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
        return r
    }
    fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

    for (i in 0 until n) {
        for (j in i + 1 until n) {
            if (Geo.haversineMeters(trails[i].center, trails[j].center) < thresholdMeters) {
                union(i, j)
            }
        }
    }

    // Bucket indices by their representative root.
    val groups = LinkedHashMap<Int, MutableList<Trail>>()
    for (i in 0 until n) {
        groups.getOrPut(find(i)) { mutableListOf() }.add(trails[i])
    }

    val systems = groups.values.mapIndexed { index, members ->
        val sorted = members.sortedBy { it.distanceMeters }
        val totalMeters = sorted.sumOf { it.lengthMeters }
        val distanceMeters = sorted.minOf { it.distanceMeters }
        val scales = sorted.mapNotNull { it.mtbScale }
        val scaleMin = scales.minOrNull()
        val scaleMax = scales.maxOrNull()
        val avgLat = sorted.sumOf { it.center.lat } / sorted.size
        val avgLon = sorted.sumOf { it.center.lon } / sorted.size
        TrailSystem(
            id = "sys_$index",
            name = systemName(sorted),
            trails = sorted,
            totalMeters = totalMeters,
            distanceMeters = distanceMeters,
            scaleMin = scaleMin,
            scaleMax = scaleMax,
            center = GeoPoint(avgLat, avgLon),
        )
    }
    return systems.sortedBy { it.distanceMeters }
}

/** Connector words trimmed from the tail of a computed common-prefix name. */
private val TRIVIAL_TAIL_WORDS = setOf("trail", "trails", "loop", "phase", "the", "and", "-", "&")

/**
 * Word-level longest common prefix of member names; if that's empty/blank, fall back to
 * the longest member's name. Trailing trivial connector words are trimmed.
 */
private fun systemName(members: List<Trail>): String {
    // Prefer the OSM park/green-space name when any member is geolocated inside one.
    // Plurality by member count; ties broken by greatest total trail length.
    val parkMembers = members.filter { !it.parkName.isNullOrBlank() }
    if (parkMembers.isNotEmpty()) {
        return parkMembers
            .groupBy { it.parkName!! }
            .maxWithOrNull(
                compareBy<Map.Entry<String, List<Trail>>> { it.value.size }
                    .thenBy { it.value.sumOf { t -> t.lengthMeters } }
            )!!.key
    }

    if (members.size == 1) return members.first().name

    val wordLists = members.map { it.name.trim().split(Regex("\\s+")).filter { w -> w.isNotBlank() } }
    val minLen = wordLists.minOf { it.size }
    val prefix = ArrayList<String>()
    for (k in 0 until minLen) {
        val word = wordLists[0][k]
        if (wordLists.all { it[k] == word }) prefix.add(word) else break
    }
    // Trim trivial trailing connector words (but never to empty).
    while (prefix.size > 1 && prefix.last().lowercase() in TRIVIAL_TAIL_WORDS) {
        prefix.removeAt(prefix.size - 1)
    }
    val name = prefix.joinToString(" ").trim()
    return if (name.isNotBlank()) name
    else members.maxByOrNull { it.lengthMeters }!!.name
}

/** One sample along a trail for the elevation chart. */
data class ElevPoint(val distanceMeters: Double, val elevationMeters: Double)

data class ElevationProfile(
    val points: List<ElevPoint>,
    val ascentMeters: Double,
    val descentMeters: Double,
    val minMeters: Double,
    val maxMeters: Double,
) {
    val ascentFeet: Double get() = ascentMeters * 3.28084
    val descentFeet: Double get() = descentMeters * 3.28084
    companion object {
        val EMPTY = ElevationProfile(emptyList(), 0.0, 0.0, 0.0, 0.0)
    }
}
