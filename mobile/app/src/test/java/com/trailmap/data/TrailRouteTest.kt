package com.trailmap.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailRouteTest {

    /** A straight north-running line, ~111 m per step. */
    private fun line(from: Int, to: Int) = (from..to).map { GeoPoint(39.0 + it * 0.001, -94.6) }

    private fun total(paths: List<List<GeoPoint>>) = paths.sumOf { Geo.lengthMeters(it) }

    @Test
    fun shuffledReversedPiecesBecomeOneRun() {
        val pieces = listOf(line(4, 6).reversed(), line(0, 2), line(6, 9), line(2, 4).reversed())
        val runs = TrailRoute.order(pieces)
        assertEquals(1, runs.size)
        assertEquals(total(pieces), TrailRoute.lengthMeters(runs), 0.01)
        // South to north, the whole way.
        assertEquals(39.0, runs[0].first().lat, 1e-9)
        assertEquals(39.009, runs[0].last().lat, 1e-9)
    }

    @Test
    fun gapIsJumpedNotMeasured() {
        val pieces = listOf(line(5, 9), line(0, 3)) // 222 m of nothing between them
        val runs = TrailRoute.order(pieces)
        assertEquals(2, runs.size)
        assertEquals(total(pieces), TrailRoute.lengthMeters(runs), 0.01)
        assertTrue(runs[0].first().lat < runs[1].first().lat)
    }

    @Test
    fun wayEndStoppingShortStillJoins() {
        val a = line(0, 3)
        // Starts 11 m east of a's last vertex, inside the snap distance.
        val b = listOf(GeoPoint(39.003, -94.59987)) + line(4, 7)
        val runs = TrailRoute.order(listOf(b, a))
        assertEquals(1, runs.size)
    }

    @Test
    fun spurSitsWhereItBranchesOff() {
        val trunk = line(0, 10)
        val spur = listOf(GeoPoint(39.005, -94.6), GeoPoint(39.005, -94.598), GeoPoint(39.005, -94.596))
        val runs = TrailRoute.order(listOf(spur, trunk))
        assertEquals(total(listOf(spur, trunk)), TrailRoute.lengthMeters(runs), 0.01)
        // The spur is ridden between the southern and northern halves, not tacked on at the end.
        val spurRun = runs.indexOfFirst { r -> r.any { it.lon > -94.599 } }
        assertTrue("spur at $spurRun of ${runs.size}", spurRun in 0 until runs.lastIndex)
        assertEquals(39.01, runs.last().last().lat, 1e-9)
    }

    @Test
    fun loopIsOneContinuousRun() {
        val ring = listOf(
            GeoPoint(39.0, -94.6), GeoPoint(39.0, -94.59), GeoPoint(39.01, -94.59),
            GeoPoint(39.01, -94.6), GeoPoint(39.0, -94.6),
        )
        val pieces = listOf(ring.subList(2, 5), ring.subList(0, 3))
        val runs = TrailRoute.order(pieces)
        assertEquals(1, runs.size)
        assertEquals(total(pieces), TrailRoute.lengthMeters(runs), 0.01)
    }

    /**
     * The real Gary L. Haller Trail (Johnson County, KS; OSM data): 81 ways, 16.26 mi. Flattened
     * in response order it measured 135.4 mi, which is what put 116 mi on the elevation chart.
     */
    @Test
    fun hallerTrailMeasuresItsRealLength() {
        val raw = javaClass.getResource("/fixtures/haller_trail_ways.json")!!.readText()
        val paths = Json.parseToJsonElement(raw).jsonArray.map { way ->
            way.jsonArray.map { p -> p.jsonArray.let { GeoPoint(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }
        }
        assertEquals(81, paths.size)
        val flattened = Geo.lengthMeters(paths.flatten())
        assertTrue("fixture should reproduce the bug: $flattened", flattened > 200_000)

        val runs = TrailRoute.order(paths)
        // Joined runs also cover the few metres between way ends that stop just short of each
        // other, so allow a little over the ways' own sum.
        assertEquals(total(paths), TrailRoute.lengthMeters(runs), 50.0)
        assertEquals(16.26, TrailRoute.lengthMeters(runs) / 1609.344, 0.01)
        // Every hop between runs is a short gap or a backtrack off a fork — no cross-trail jumps.
        val hops = (1 until runs.size).map { Geo.haversineMeters(runs[it - 1].last(), runs[it].first()) }
        assertTrue("longest hop ${hops.max()} m", hops.max() < 1_500)
        // It runs south to north.
        assertTrue(runs.first().first().lat < 38.91)
        assertTrue(runs.last().last().lat > 39.03)
    }
}
