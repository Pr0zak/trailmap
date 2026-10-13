package com.trailmap.data

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/** A lat/lon viewport (for "download the current map view" offline). */
data class ViewBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val zoom: Double,
)

/** One trail in a [Ride] — a serializable snapshot so totals work offline / across areas. */
@Serializable
data class RideTrail(
    val id: String,
    val name: String,
    val lengthMeters: Double,
    val surface: String,        // SurfaceType.name
    val mtbScale: Int? = null,
    /** [DifficultyTier] name; null on rides saved before it was recorded (then [mtbScale] decides). */
    val level: String? = null,
    // Where the trail is, so "Show ride on map" can go there. Null on rides saved before
    // this was recorded; those still highlight once their trails are loaded.
    val lat: Double? = null,
    val lon: Double? = null,
)

/** A user-built ride: a named set of trails whose lengths sum into a total. */
@Serializable
data class Ride(
    val id: String,
    val name: String,
    val trails: List<RideTrail> = emptyList(),
) {
    val totalMeters: Double get() = trails.sumOf { it.lengthMeters }
    val totalMiles: Double get() = totalMeters / 1609.344
    /** Length share per surface bucket name, for a simple breakdown. */
    val surfaceMix: Map<String, Double>
        get() = trails.groupBy { it.surface }.mapValues { (_, ts) -> ts.sumOf { it.lengthMeters } }
}

/** A single lat/lon vertex. Domain type kept independent of MapLibre's LatLng. */
data class GeoPoint(val lat: Double, val lon: Double)

/** Surface buckets we render + filter by. Raw OSM surface=* values map into these. */
enum class SurfaceType(val label: String, val color: Color) {
    PAVED("Paved", Color(0xFF2E7D4F)),   // green
    GRAVEL("Gravel", Color(0xFFDAA520)), // goldenrod — distinct gold/yellow
    DIRT("Dirt", Color(0xFFA0522D)),     // sienna — distinct red-brown
    UNKNOWN("Unknown", Color(0xFF616161));

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

/** Show, hide, or show only horse trails ([Trail.horseTrail]). */
enum class HorseTrailFilter(val label: String) { SHOW("Show"), HIDE("Hide"), ONLY("Only") }

/** Intended use, derived from OSM bicycle/foot designation + highway type. */
enum class UseType(val label: String) { WALK("Walking"), BIKE("Biking"), HORSE("Horseback") }

/**
 * A trail's difficulty as its sign shows it — Trailforks' six-level scale, which extends the
 * ski/IMBA signs: white (easiest), green circle (easy), blue square (intermediate), black
 * diamond (advanced), double black (expert) and orange "pro line" (beyond expert).
 *
 * Two OSM tags feed it. `mtb:scale:imba` *is* the posted sign colour (0 white … 4 double
 * black) and wins when present; `mtb:scale` (S0-S6, technical difficulty) maps onto it
 * otherwise. They disagree sometimes — Fraggle Rock in KC is signed black but tagged S2 — and
 * the sign is what a rider sees at the trailhead.
 */
enum class DifficultyTier(val label: String, val symbol: String, val color: Color) {
    WHITE("Easiest", "○", Color(0xFFFFFFFF)),
    GREEN("Easy", "●", Color(0xFF2E7D32)),
    BLUE("Intermediate", "■", Color(0xFF1565C0)),
    BLACK("Advanced", "◆", Color(0xFF1A1A1A)),
    DOUBLE_BLACK("Expert", "◆◆", Color(0xFF1A1A1A)),
    PRO("Pro", "▲", Color(0xFFBF360C));

    /** Label colour on [color] (>= 4.5:1): dark on the white badge, white on the rest. */
    val onColor: Color get() = if (this == WHITE) Color(0xFF1A1A1A) else Color.White

    /** The white badge needs an outline to show on a light card. */
    val outlined: Boolean get() = this == WHITE

    companion object {
        /** OSM mtb:scale S0-S6: S0 white, S1 green, S2 blue, S3 black, S4 double black, S5-S6 pro. */
        fun fromScale(scale: Int?): DifficultyTier? = when (scale) {
            0 -> WHITE
            1 -> GREEN
            2 -> BLUE
            3 -> BLACK
            4 -> DOUBLE_BLACK
            5, 6 -> PRO
            else -> null
        }

        /** OSM mtb:scale:imba, the sign colour: 0 white, 1 green, 2 blue, 3 black, 4 double black. */
        fun fromImba(imba: Int?): DifficultyTier? = when (imba) {
            0 -> WHITE
            1 -> GREEN
            2 -> BLUE
            3 -> BLACK
            4 -> DOUBLE_BLACK
            else -> null
        }

        /** The sign colour when tagged, else the S-scale's. */
        fun of(scale: Int?, imba: Int?): DifficultyTier? = fromImba(imba) ?: fromScale(scale)

        fun byName(name: String?): DifficultyTier? = entries.firstOrNull { it.name == name }
    }
}

/** Badge text: the sign and its name, plus the S-scale when OSM has one, e.g. "■ Intermediate · S2". */
fun difficultyText(level: DifficultyTier, scale: Int?): String =
    "${level.symbol} ${level.label}" + (scale?.let { " · S$it" } ?: "")

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
    /**
     * Per entry of [paths]: that piece is a horse trail — a bridleway or a horse-designated path
     * that bikes aren't allowed on (walkers may be). Not merely open to horses: most horse-legal
     * miles are multi-use trails (the Flint Hills Trail, 96% of the Katy Trail's horse
     * sections), which keep their surface colour. Per piece, because a named trail can mix
     * both. Empty means none.
     */
    val horseTrailPaths: List<Boolean> = emptyList(),
    /**
     * The trail's difficulty: the relation's own rating, else its hardest piece's. Unlike
     * [mtbScale] it also counts the sign colour (`mtb:scale:imba`), which some trails carry alone.
     */
    val level: DifficultyTier? = null,
    /** Per entry of [paths]: that piece's difficulty, for colouring the map by section. */
    val levelPaths: List<DifficultyTier?> = emptyList(),
    /**
     * Per entry of [paths]: 1 when that piece is one-way in its drawn direction, -1 when
     * one-way against it (OSM `oneway=-1`), 0 when two-way. Empty means all two-way.
     */
    val onewayPaths: List<Int> = emptyList(),
    /** Who maintains it (OSM `operator`), e.g. "Kansas City Parks & Recreation". */
    val operator: String? = null,
    /** The trail's or its operator's page — http(s) only, since map data is open to edit. */
    val website: String? = null,
    /** Mostly (by length) a horse trail — what the "Horse trails" filter and the list go by. */
    val horseTrail: Boolean = false,
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
    val levelMin: DifficultyTier?,  // easiest member's level (null if none rated)
    val levelMax: DifficultyTier?,
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
        val levels = sorted.mapNotNull { it.level }
        val avgLat = sorted.sumOf { it.center.lat } / sorted.size
        val avgLon = sorted.sumOf { it.center.lon } / sorted.size
        TrailSystem(
            id = "sys_$index",
            name = systemName(sorted),
            trails = sorted,
            totalMeters = totalMeters,
            distanceMeters = distanceMeters,
            levelMin = levels.minOrNull(),
            levelMax = levels.maxOrNull(),
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
/** One elevation sample: distance along the trail, height, and where on the trail it was taken. */
data class ElevPoint(
    val distanceMeters: Double,
    val elevationMeters: Double,
    val point: GeoPoint? = null,
    /** First sample after a gap between a trail's pieces; the chart and climb don't join across it. */
    val gapBefore: Boolean = false,
)

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
