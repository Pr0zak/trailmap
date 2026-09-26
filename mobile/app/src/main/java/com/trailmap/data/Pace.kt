package com.trailmap.data

/** One of your average speeds, and how many recordings it's based on. */
data class Pace(val kind: ActivityKind, val mph: Double, val count: Int) {
    /** How the detail screen names it: "your 9.1 mph e-bike average". */
    val noun: String
        get() = when (kind) {
            ActivityKind.EBIKE -> "e-bike"
            ActivityKind.MTB -> "mountain-bike"
            ActivityKind.BIKE -> "bike"
            else -> "walking"
        }
}

/**
 * Your average speeds by kind of outing, from recorded activities — what the trail detail's
 * time estimate uses instead of a flat 10 mph by bike and 3 mph on foot. One flat figure
 * can't be right for a greenway, singletrack and an e-bike at once, and a rider's own
 * recordings already say what their pace is on each.
 *
 * Averages are distance over recorded duration, which may include stops; the tracks carry no
 * timestamps, so moving time isn't available.
 */
data class PersonalPace(
    val bike: Pace?,
    val ebike: Pace?,
    val mtb: Pace?,
    val walk: Pace?,
    /** Rides of each kind in the last year, to tell which bike you usually take. */
    val recentBikeRides: Int = 0,
    val recentEbikeRides: Int = 0,
) {
    /**
     * The speed to plan [trail] at: mountain-bike pace on rated singletrack and dirt bike
     * trails, your usual bike where bikes are allowed, and walking pace where they aren't.
     * Null when nothing of that kind is recorded.
     */
    fun forTrail(trail: Trail): Pace? {
        val usualBike = if (recentEbikeRides > recentBikeRides) ebike ?: bike else bike ?: ebike
        val bikes = UseType.BIKE in trail.uses
        return when {
            trail.mtbScale != null || (bikes && trail.surface == SurfaceType.DIRT) -> mtb ?: usualBike
            bikes -> usualBike
            else -> walk
        }
    }

    companion object {
        private const val YEAR_MS = 365L * 24 * 3600 * 1000

        fun from(tracks: List<RecordedTrack>, now: Long = System.currentTimeMillis()): PersonalPace {
            fun pace(kinds: Set<ActivityKind>, sane: ClosedFloatingPointRange<Double>): Pace? {
                val usable = tracks.filter { t ->
                    t.kind in kinds && (t.distanceM ?: 0.0) >= 800.0 && t.durationS >= 300 &&
                        t.mph?.let { it in sane } == true
                }
                // Recent riding says more about today's pace than a decade-old average, as
                // long as there is enough of it to average.
                val recent = usable.filter { now - it.start <= 2 * YEAR_MS }
                val basis = if (recent.size >= 3) recent else usable
                if (basis.isEmpty()) return null
                val meters = basis.sumOf { it.distanceM ?: 0.0 }
                val hours = basis.sumOf { it.durationS } / 3600.0
                return Pace(kinds.first(), meters / 1609.344 / hours, basis.size)
            }
            fun recent(kind: ActivityKind) = tracks.count { it.kind == kind && now - it.start <= YEAR_MS }
            return PersonalPace(
                bike = pace(setOf(ActivityKind.BIKE), 3.0..30.0),
                ebike = pace(setOf(ActivityKind.EBIKE), 4.0..35.0),
                mtb = pace(setOf(ActivityKind.MTB), 2.0..20.0),
                walk = pace(setOf(ActivityKind.WALK, ActivityKind.HIKE), 0.8..5.0),
                recentBikeRides = recent(ActivityKind.BIKE),
                recentEbikeRides = recent(ActivityKind.EBIKE),
            )
        }
    }
}
