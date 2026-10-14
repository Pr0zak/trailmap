package com.trailmap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Levels set on the phone: applied over the map's, reversible, and kept to the right trail. */
class UserLevelsTest {
    private fun trail(id: String, lat: Double = 39.4, levels: List<DifficultyTier?> = listOf(null, null)) = Trail(
        id, "Smith's Fork Trail", SurfaceType.DIRT, mapOf(SurfaceType.DIRT to 1.0), setOf(UseType.BIKE), 2000.0, 0.0,
        levels.indices.map { i -> listOf(GeoPoint(lat, -94.5 + i * 0.01), GeoPoint(lat + 0.005, -94.5 + i * 0.01)) },
        GeoPoint(lat, -94.5),
        level = levels.filterNotNull().maxOrNull(),
        levelPaths = levels,
    )

    @Test fun anUnratedTrailTakesYourLevelOnEveryPiece() {
        val t = trail("name_smiths_fork_trail")
        val rated = UserLevels.apply(t, mapOf(t.id to UserLevels.of(t, DifficultyTier.BLUE)))
        assertEquals(DifficultyTier.BLUE, rated.level)
        assertEquals(listOf(DifficultyTier.BLUE, DifficultyTier.BLUE), rated.levelPaths)
        assertEquals(DifficultyTier.BLUE, rated.userLevel)
    }

    @Test fun clearingBringsTheMapsRatingBack() {
        val t = trail("way_1", levels = listOf(DifficultyTier.GREEN, DifficultyTier.BLACK))
        val rated = UserLevels.apply(t, mapOf(t.id to UserLevels.of(t, DifficultyTier.BLUE)))
        val back = UserLevels.apply(rated, emptyMap())
        assertEquals(DifficultyTier.BLACK, back.level)
        assertEquals(listOf(DifficultyTier.GREEN, DifficultyTier.BLACK), back.levelPaths)
        assertNull(back.userLevel)
        assertEquals(t, back)
    }

    @Test fun changingTwiceKeepsTheMapsOriginal() {
        val t = trail("way_1", levels = listOf(DifficultyTier.GREEN, null))
        val a = UserLevels.apply(t, mapOf(t.id to UserLevels.of(t, DifficultyTier.BLUE)))
        val b = UserLevels.apply(a, mapOf(t.id to UserLevels.of(t, DifficultyTier.BLACK)))
        assertEquals(DifficultyTier.BLACK, b.level)
        assertEquals(DifficultyTier.GREEN, b.mapLevel)
        assertEquals(listOf(DifficultyTier.GREEN, null), b.mapLevelPaths)
    }

    @Test fun aSameNamedTrailElsewhereIsNotRated() {
        val here = trail("name_connector")
        val levels = mapOf(here.id to UserLevels.of(here, DifficultyTier.BLUE))
        assertNull(UserLevels.find(trail("name_connector", lat = 38.6), levels)) // ~90 km away
        assertEquals(DifficultyTier.BLUE, UserLevels.find(trail("name_connector", lat = 39.43), levels))
    }

    @Test fun nothingToDoLeavesTheListAlone() {
        val list = listOf(trail("way_1"), trail("way_2"))
        assertSame(list, UserLevels.applyAll(list, emptyMap()))
    }
}
