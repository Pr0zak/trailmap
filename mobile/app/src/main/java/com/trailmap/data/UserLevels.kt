package com.trailmap.data

import kotlinx.serialization.Serializable

/**
 * Levels you set yourself, for trails the map doesn't rate (Smithville Lake's trails carry no
 * `mtb:scale` or sign colour at all) or rates differently from the sign. Kept on the phone
 * only, in [Prefs], keyed by trail id.
 */
@Serializable
data class UserLevel(
    /** A [DifficultyTier] name. */
    val level: String,
    /** Where the trail was when you rated it — see [UserLevels.SAME_TRAIL_M]. */
    val lat: Double,
    val lon: Double,
)

object UserLevels {
    /**
     * Named trails' ids are their name (`name_connector`), so two "Connector"s in different
     * towns share one. A rating only applies to a trail with that id centred within this
     * distance of where it was set. Way and relation ids are unique, so they skip the check.
     */
    const val SAME_TRAIL_M = 10_000.0

    fun of(trail: Trail, tier: DifficultyTier): UserLevel =
        UserLevel(tier.name, trail.center.lat, trail.center.lon)

    /** The level you set for [trail], if any. */
    fun find(trail: Trail, levels: Map<String, UserLevel>): DifficultyTier? {
        val u = levels[trail.id] ?: return null
        if (trail.id.startsWith("name_") &&
            Geo.haversineMeters(trail.center, GeoPoint(u.lat, u.lon)) > SAME_TRAIL_M
        ) return null
        return DifficultyTier.byName(u.level)
    }

    /**
     * [trail] with your level on every piece, or with the map's rating back when you have
     * none for it. Safe to call again on a trail it already changed.
     */
    fun apply(trail: Trail, levels: Map<String, UserLevel>): Trail {
        val base = if (trail.userLevel == null) trail else trail.copy(
            level = trail.mapLevel,
            levelPaths = trail.mapLevelPaths,
            userLevel = null,
            mapLevel = null,
            mapLevelPaths = emptyList(),
        )
        val tier = find(base, levels) ?: return base
        return base.copy(
            userLevel = tier,
            level = tier,
            levelPaths = List(base.paths.size) { tier },
            mapLevel = base.level,
            mapLevelPaths = base.levelPaths,
        )
    }

    fun applyAll(trails: List<Trail>, levels: Map<String, UserLevel>): List<Trail> =
        if (levels.isEmpty() && trails.none { it.userLevel != null }) trails
        else trails.map { apply(it, levels) }
}
