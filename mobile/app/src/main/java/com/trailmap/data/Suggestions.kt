package com.trailmap.data

/**
 * Suggested rides, from data the app already holds — no service tells us what other people
 * ride (Strava's heatmap and Trailforks' popular routes have no open API), so:
 *  - **Rides you repeat**: your recorded rides grouped by the trails they used. Rides whose
 *    trail sets mostly overlap are one habit; a habit seen twice or more is a suggestion.
 *  - **Named routes**: OSM `route=mtb` relations — loops and routes local trail groups mapped
 *    and named themselves (Wudchuk Run, Krug Park Trail).
 */
data class SuggestedRide(
    /** `repeat_<…>` or `route_<relation trail id>`, stable across recomputes. */
    val id: String,
    val kind: Kind,
    val name: String,
    /** In riding order where known (a repeat's latest ride; a route's own geometry). */
    val trails: List<Trail>,
    /** Rides that took this combination, newest first (repeats only). */
    val rideIds: List<String> = emptyList(),
    val lastRidden: Long? = null,
) {
    enum class Kind { REPEAT, ROUTE }

    val lengthMeters: Double get() = trails.sumOf { it.lengthMeters }
    val lengthMiles: Double get() = lengthMeters / 1609.344
    val timesRidden: Int get() = rideIds.size

    /** The hardest level among the trails, or null when none is rated. */
    val level: DifficultyTier? get() = trails.mapNotNull { it.level }.maxOrNull()

    /** Metres per level across the trails' pieces; unrated pieces under null. */
    val levelMix: Map<DifficultyTier?, Double>
        get() {
            val mix = LinkedHashMap<DifficultyTier?, Double>()
            for (t in trails) {
                t.paths.forEachIndexed { i, path ->
                    val lvl = t.levelPaths.getOrNull(i) ?: t.level
                    mix[lvl] = (mix[lvl] ?: 0.0) + Geo.lengthMeters(path)
                }
            }
            return mix
        }

    val center: GeoPoint
        get() = GeoPoint(trails.map { it.center.lat }.average(), trails.map { it.center.lon }.average())
}

object Suggestions {
    /** Two rides are the same habit when their trail sets overlap this much (Jaccard). */
    const val SAME_RIDE = 0.6

    /** A combination ridden at least this often is suggested. */
    const val MIN_REPEATS = 2

    /**
     * Group rides by the trails they used. [ridesByTrail] maps trail id → the ride ids that
     * went along it ([TrailVisits.rides]); [rideStart] gives each ride's start time. Returns
     * clusters as (trail ids in at least half the cluster's rides, ride ids newest first),
     * most-ridden first. Rides along fewer than two trails are skipped — "rode the Trolley
     * Track" isn't a combination.
     */
    fun repeatClusters(
        ridesByTrail: Map<String, List<String>>,
        rideStart: (String) -> Long,
    ): List<Pair<Set<String>, List<String>>> {
        val trailsByRide = HashMap<String, MutableSet<String>>()
        for ((trail, rides) in ridesByTrail) for (r in rides) trailsByRide.getOrPut(r) { HashSet() }.add(trail)
        val rides = trailsByRide.filterValues { it.size >= 2 }

        // Greedy: newest ride first seeds a cluster; later rides join the first cluster whose
        // seed set they overlap enough. Comparing against the seed, not a growing union, keeps
        // one long ride from dragging every short loop into its cluster.
        class Cluster(val seed: Set<String>, val rides: MutableList<String>)
        val clusters = ArrayList<Cluster>()
        for (ride in rides.keys.sortedByDescending(rideStart)) {
            val set = rides.getValue(ride)
            val home = clusters.firstOrNull { jaccard(it.seed, set) >= SAME_RIDE }
            if (home != null) home.rides.add(ride) else clusters.add(Cluster(set, mutableListOf(ride)))
        }
        return clusters.filter { it.rides.size >= MIN_REPEATS }.map { c ->
            val counts = HashMap<String, Int>()
            for (r in c.rides) for (t in rides.getValue(r)) counts[t] = (counts[t] ?: 0) + 1
            val core = counts.filterValues { it * 2 >= c.rides.size }.keys
            core to c.rides
        }.filter { it.first.size >= 2 }.sortedByDescending { it.second.size }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /** A short name from the longest trails: "Wudchuk Run + Lower Wudchuck + 2 more". */
    fun nameFor(trails: List<Trail>): String {
        val byLength = trails.sortedByDescending { it.lengthMeters }
        val head = byLength.take(2).joinToString(" + ") { it.name }
        return if (byLength.size > 2) "$head + ${byLength.size - 2} more" else head
    }

    /** Named routes shorter than this are connectors and stubs, not a ride. */
    const val MIN_ROUTE_M = 1_000.0

    /** Routes are ranked within distance bands this wide, longest first inside each. */
    private const val ROUTE_BAND_M = 5_000.0

    /**
     * Named route relations among [trails]: at least [MIN_ROUTE_M] long, the nearest band
     * first and the longest routes first within it — nearest-first alone put 0.1 mi stubs
     * like Swope's "Birdbath" above the loops worth riding.
     */
    fun routes(trails: List<Trail>, center: GeoPoint, limit: Int = 25): List<SuggestedRide> =
        trails.asSequence()
            .filter { it.id.startsWith("rel_") && it.name != "Unnamed path" && it.lengthMeters >= MIN_ROUTE_M }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<Trail> { (Geo.haversineMeters(it.center, center) / ROUTE_BAND_M).toInt() }
                    .thenByDescending { it.lengthMeters },
            )
            .take(limit)
            .map { SuggestedRide("route_${it.id}", SuggestedRide.Kind.ROUTE, it.name, listOf(it)) }
            .toList()
}
