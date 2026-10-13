package com.trailmap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grouping recorded rides into "rides you repeat", and picking named routes. */
class SuggestionsTest {
    private val start = mapOf("r1" to 100L, "r2" to 200L, "r3" to 300L, "r4" to 400L, "r5" to 500L)

    @Test fun ridesOverTheSameTrailsAreOneHabit() {
        // r1, r2, r4 ride A+B+C (r4 adds D); r3 rides only A; r5 rides E+F once.
        val byTrail = mapOf(
            "A" to listOf("r1", "r2", "r3", "r4"),
            "B" to listOf("r1", "r2", "r4"),
            "C" to listOf("r1", "r2", "r4"),
            "D" to listOf("r4"),
            "E" to listOf("r5"),
            "F" to listOf("r5"),
        )
        val clusters = Suggestions.repeatClusters(byTrail) { start.getValue(it) }
        assertEquals(1, clusters.size)
        val (trails, rides) = clusters.single()
        assertEquals(setOf("A", "B", "C"), trails) // D is in one ride of three: not core
        assertEquals(listOf("r4", "r2", "r1"), rides) // newest first; r3 (one trail) skipped
    }

    @Test fun differentLoopsStayApart() {
        val byTrail = mapOf(
            "A" to listOf("r1", "r2"), "B" to listOf("r1", "r2"),
            "X" to listOf("r3", "r4"), "Y" to listOf("r3", "r4"),
        )
        val clusters = Suggestions.repeatClusters(byTrail) { start.getValue(it) }
        assertEquals(setOf(setOf("A", "B"), setOf("X", "Y")), clusters.map { it.first }.toSet())
    }

    @Test fun aSingleRideIsNotASuggestion() {
        val byTrail = mapOf("A" to listOf("r1"), "B" to listOf("r1"))
        assertTrue(Suggestions.repeatClusters(byTrail) { start.getValue(it) }.isEmpty())
    }

    private fun trail(id: String, name: String, km: Double, lat: Double = 39.0) = Trail(
        id, name, SurfaceType.DIRT, mapOf(SurfaceType.DIRT to 1.0), setOf(UseType.BIKE), km * 1000, 0.0,
        listOf(listOf(GeoPoint(lat, -94.5), GeoPoint(lat + km / 111.0, -94.5))), GeoPoint(lat, -94.5),
    )

    @Test fun namesUseTheLongestTrails() {
        val t = listOf(trail("a", "Short", 0.5), trail("b", "Wudchuk Run", 6.0), trail("c", "Lower Wudchuck", 2.0), trail("d", "Connector", 0.2))
        assertEquals("Wudchuk Run + Lower Wudchuck + 2 more", Suggestions.nameFor(t))
    }

    @Test fun routesAreNamedRelationsLongestNearbyFirst() {
        val t = listOf(
            trail("rel_2", "Far Loop", 9.0, lat = 39.5),
            trail("name_x", "Some Way", 3.0),
            trail("rel_1", "Near Loop", 3.0, lat = 39.01),
            trail("rel_3", "Big Near Loop", 6.0, lat = 39.02),
            trail("rel_4", "Birdbath", 0.2, lat = 39.0),
        )
        val routes = Suggestions.routes(t, GeoPoint(39.0, -94.5))
        // Same 5 km band: longest first; the far one after; the 0.2 km stub not at all.
        assertEquals(listOf("Big Near Loop", "Near Loop", "Far Loop"), routes.map { it.name })
        assertEquals(SuggestedRide.Kind.ROUTE, routes.first().kind)
    }
}
