package com.trailmap.data

/**
 * Which trail-status board entry (see [TrailStatus]) applies to a trail or a trail system.
 *
 * The board names parks the way their trail crews do ("Shawnee Mission Park North Trails -
 * Bike, Hike"), and trailmap names systems after the OSM park around them ("Shawnee Mission
 * Park"), so names are compared as word sets after dropping the generic words, and the
 * nearest trailhead breaks ties and fills in when no name fits.
 */
object TrailConditions {
    /** A name match only counts within this distance of the system or trail. */
    private const val NAME_REACH_M = 15_000.0

    /** Without a name match, the trailhead has to be this close to one of the system's trails… */
    private const val SYSTEM_REACH_M = 1_500.0

    /** …or this close to a single trail's line. */
    private const val TRAIL_REACH_M = 1_000.0

    private val GENERIC = setOf(
        "park", "parks", "trail", "trails", "the", "and", "of", "mtb", "singletrack", "area",
        "bike", "hike", "hiking", "biking", "mountain", "north", "south", "east", "west",
    )

    fun forSystem(system: TrailSystem, statuses: List<TrailStatus>): TrailStatus? {
        if (statuses.isEmpty()) return null
        return byName(system.name, system.trails, statuses)
            ?: nearest(system.trails, statuses, SYSTEM_REACH_M)
    }

    /**
     * The status for one trail. Paved trails get none: a greenway doesn't close for rain,
     * however close it runs to a mountain-bike trailhead.
     */
    fun forTrail(trail: Trail, statuses: List<TrailStatus>): TrailStatus? {
        if (statuses.isEmpty()) return null
        if (trail.mtbScale == null && trail.surface == SurfaceType.PAVED) return null
        return trail.parkName?.let { byName(it, listOf(trail), statuses) }
            ?: nearest(listOf(trail), statuses, TRAIL_REACH_M)
    }

    internal fun words(name: String): Set<String> =
        name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in GENERIC }.toSet()

    private fun byName(name: String, trails: List<Trail>, statuses: List<TrailStatus>): TrailStatus? {
        val mine = words(name)
        if (mine.isEmpty()) return null
        return statuses
            .filter { s ->
                val theirs = words(s.name)
                theirs.isNotEmpty() && (mine.containsAll(theirs) || theirs.containsAll(mine))
            }
            .map { it to distanceTo(it, trails) }
            .filter { (_, d) -> d == null || d <= NAME_REACH_M }
            .minByOrNull { (_, d) -> d ?: Double.MAX_VALUE }
            ?.first
    }

    private fun nearest(trails: List<Trail>, statuses: List<TrailStatus>, reach: Double): TrailStatus? =
        statuses.mapNotNull { s -> distanceTo(s, trails)?.let { s to it } }
            .filter { (_, d) -> d <= reach }
            .minByOrNull { (_, d) -> d }
            ?.first

    /**
     * Metres from the status's trailhead to the nearest vertex of any of [trails]. Flat-earth
     * arithmetic rather than haversine: at trailhead distances it is exact enough, and it runs
     * over every vertex of every system on the list for each board entry.
     */
    private fun distanceTo(s: TrailStatus, trails: List<Trail>): Double? {
        val p = s.point ?: return null
        val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(p.lat))
        var best = Double.MAX_VALUE
        for (t in trails) for (path in t.paths) for (v in path) {
            val dx = (v.lon - p.lon) * kx
            val dy = (v.lat - p.lat) * 110_574.0
            val d2 = dx * dx + dy * dy
            if (d2 < best) best = d2
        }
        return if (best == Double.MAX_VALUE) null else kotlin.math.sqrt(best)
    }
}
