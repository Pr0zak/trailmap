package com.trailmap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * Matching recorded tracks to trails. All geometry here is synthetic: straight lines laid out
 * in metres around a point in Kansas City, never anyone's real track.
 */
class RideMatchTest {
    private val origin = GeoPoint(39.0, -94.6)

    /** A point [east] and [north] metres from [origin]. */
    private fun at(east: Double, north: Double) = GeoPoint(
        origin.lat + north / 111_132.0,
        origin.lon + east / (111_132.0 * cos(Math.toRadians(origin.lat))),
    )

    /** A straight line from (x0, y0) to (x1, y1) in metres, with a vertex every [step] metres. */
    private fun line(x0: Double, y0: Double, x1: Double, y1: Double, step: Double = 100.0): List<GeoPoint> {
        val len = Math.hypot(x1 - x0, y1 - y0)
        val n = maxOf(1, (len / step).toInt())
        return (0..n).map { i -> at(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n) }
    }

    private fun trail(id: String, vararg paths: List<GeoPoint>) = Trail(
        id = id, name = id, surface = SurfaceType.PAVED, surfaceMix = mapOf(SurfaceType.PAVED to 1.0),
        uses = setOf(UseType.WALK, UseType.BIKE), lengthMeters = paths.sumOf { Geo.lengthMeters(it) },
        distanceMeters = 0.0, paths = paths.toList(), center = paths[0][paths[0].size / 2],
    )

    private fun track(id: String, type: String, start: Long, pts: List<GeoPoint>) =
        RecordedTrack(id, type, null, start, 3600, Geo.lengthMeters(pts), encode(pts))

    /** Straight 2 km east–west trail along y = 0. */
    private val main = trail("main", line(0.0, 0.0, 2000.0, 0.0))

    @Test fun decodesGooglesReferencePolyline() {
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-9)
        assertEquals(-120.2, pts[0].lon, 1e-9)
        assertEquals(40.7, pts[1].lat, 1e-9)
        assertEquals(-120.95, pts[1].lon, 1e-9)
        assertEquals(43.252, pts[2].lat, 1e-9)
        assertEquals(-126.453, pts[2].lon, 1e-9)
    }

    @Test fun truncatedPolylineKeepsWhatDecoded() {
        assertEquals(2, Polyline.decode("_p~iF~ps|U_ulLnnqC_mqN").size)
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test fun encoderRoundTrips() {
        val pts = line(0.0, 0.0, 700.0, 300.0, step = 37.0)
        val back = Polyline.decode(encode(pts))
        assertEquals(pts.size, back.size)
        pts.zip(back).forEach { (a, b) -> assertTrue(Geo.haversineMeters(a, b) < 1.5) }
    }

    @Test fun rideAlongsideCounts() {
        val index = TrackIndex(listOf(track("s:1", "cycling", 1_000, line(-50.0, 10.0, 2050.0, 10.0))))
        val v = index.visits(main)!!
        assertEquals(listOf("s:1"), v.rides)
        assertTrue(v.onFoot.isEmpty())
        assertEquals(1_000L, v.lastRidden)
        assertTrue("fraction ${v.riddenFraction}", v.riddenFraction > 0.99)
        assertEquals(1, v.riddenPaths.size)
    }

    @Test fun riddenStretchKeepsTheTrailsVerticesNotEverySample() {
        // 2 km of trail with a vertex every 100 m (21 vertices) is sampled every 10 m (200
        // pieces); the stretch drawn for it should carry the vertices, not 200 points.
        val index = TrackIndex(listOf(track("s:1", "cycling", 1_000, line(-50.0, 10.0, 2050.0, 10.0))))
        val stretch = index.visits(main)!!.riddenPaths.single()
        assertTrue("points ${stretch.size}", stretch.size <= 22)
        assertTrue(Geo.haversineMeters(stretch.first(), at(0.0, 0.0)) < 1)
        assertTrue(Geo.haversineMeters(stretch.last(), at(2000.0, 0.0)) < 1)
        // Still the same line: its length is the trail's.
        assertEquals(2000.0, Geo.lengthMeters(stretch), 5.0)
    }

    @Test fun parallelRoadFortyMetresAwayDoesNot() {
        val index = TrackIndex(listOf(track("s:1", "cycling", 1_000, line(0.0, 40.0, 2000.0, 40.0))))
        assertNull(index.visits(main))
    }

    @Test fun crossingDoesNot() {
        val index = TrackIndex(listOf(track("s:1", "cycling", 1_000, line(1000.0, -800.0, 1000.0, 800.0))))
        assertNull(index.visits(main))
    }

    @Test fun halfTheTrailIsHalfCovered() {
        val index = TrackIndex(listOf(track("s:1", "ebikeride", 1_000, line(0.0, 5.0, 1000.0, 5.0))))
        val v = index.visits(main)!!
        assertEquals(0.5, v.riddenFraction, 0.02)
        // The ridden stretch is the western kilometre, give or take the match distance at the
        // end where the track stops.
        val stretch = v.riddenPaths.single()
        assertTrue(Geo.haversineMeters(stretch.first(), at(0.0, 0.0)) < 15)
        assertTrue(Geo.haversineMeters(stretch.last(), at(1000.0, 0.0)) <= TrackIndex.MATCH_M + 10)
    }

    @Test fun twoPartialRidesCoverTheirUnion() {
        val index = TrackIndex(
            listOf(
                track("s:1", "cycling", 1_000, line(0.0, 5.0, 800.0, 5.0)),
                track("s:2", "cycling", 2_000, line(1200.0, -5.0, 2000.0, -5.0)),
            ),
        )
        val v = index.visits(main)!!
        assertEquals(listOf("s:2", "s:1"), v.rides) // newest first
        assertEquals(0.8, v.riddenFraction, 0.02)
        assertEquals(2, v.riddenPaths.size)
    }

    @Test fun shortTrailNeedsHalfNotThreeHundredMetres() {
        val short = trail("short", line(0.0, 0.0, 200.0, 0.0, step = 50.0))
        val covered = TrackIndex(listOf(track("s:1", "cycling", 1, line(0.0, 3.0, 120.0, 3.0))))
        assertNotNull(covered.visits(short))
        val clipped = TrackIndex(listOf(track("s:1", "cycling", 1, line(0.0, 3.0, 50.0, 3.0))))
        assertNull(clipped.visits(short))
    }

    @Test fun walksCountOnFootAndNotAsRides() {
        val index = TrackIndex(
            listOf(
                track("g:1", "walking", 5_000, line(0.0, -8.0, 2000.0, -8.0)),
                track("g:2", "kayaking", 6_000, line(0.0, 0.0, 2000.0, 0.0)),
            ),
        )
        val v = index.visits(main)!!
        assertTrue(v.rides.isEmpty())
        assertEquals(listOf("g:1"), v.onFoot)
        assertEquals(0.0, v.riddenMeters, 0.0)
        assertTrue(v.onFootFraction > 0.99)
        assertTrue(v.riddenPaths.isEmpty())
    }

    @Test fun trailsComeBackInRidingOrder() {
        // East along the main trail, then north up a second one.
        val north = trail("north", line(2000.0, 0.0, 2000.0, 1500.0))
        val far = trail("far", line(0.0, 5000.0, 1000.0, 5000.0))
        val ride = line(0.0, 6.0, 2000.0, 6.0) + line(2006.0, 0.0, 2006.0, 1500.0)
        val index = TrackIndex(listOf(track("s:9", "ride", 1, ride)))
        val along = index.trailsAlong("s:9", listOf(north, far, main))
        assertEquals(listOf("main", "north"), along.map { it.trail.id })
        assertEquals(2000.0, along[0].coveredMeters, 40.0)
    }

    @Test fun trailsFarFromEveryTrackCostNothing() {
        val index = TrackIndex(listOf(track("s:1", "cycling", 1, line(0.0, 0.0, 2000.0, 0.0))))
        val elsewhere = trail("elsewhere", line(50_000.0, 50_000.0, 52_000.0, 50_000.0))
        assertNull(index.visits(elsewhere))
    }

    @Test fun manyTracksAndTrailsStayQuick() {
        // A few hundred rides over a 20 km square, as a sanity bound on the grid.
        val rnd = java.util.Random(7)
        val tracks = (0 until 400).map { i ->
            val x = rnd.nextDouble() * 20_000; val y = rnd.nextDouble() * 20_000
            track("s:$i", "cycling", i.toLong(), line(x, y, x + rnd.nextDouble() * 15_000 - 7_500, y + rnd.nextDouble() * 15_000 - 7_500, step = 60.0))
        }
        val trails = (0 until 500).map { i ->
            val x = rnd.nextDouble() * 20_000; val y = rnd.nextDouble() * 20_000
            trail("t$i", line(x, y, x + 1500, y + rnd.nextDouble() * 400, step = 30.0))
        }
        val t0 = System.nanoTime()
        val index = TrackIndex(tracks)
        val built = System.nanoTime()
        trails.forEach { index.visits(it) }
        val done = System.nanoTime()
        println("index ${(built - t0) / 1_000_000} ms, 500 trails ${(done - built) / 1_000_000} ms")
        assertTrue((done - t0) / 1_000_000 < 20_000)
    }

    @Test fun activityKindsFromMyVitalsTypes() {
        mapOf(
            "cycling" to ActivityKind.BIKE,
            "ebikeride" to ActivityKind.EBIKE,
            "mountain_biking" to ActivityKind.MTB,
            "bike" to ActivityKind.BIKE,
            "ride" to ActivityKind.BIKE,
            "outdoor_bike" to ActivityKind.BIKE,
            "virtualride" to ActivityKind.OTHER,
            "walking" to ActivityKind.WALK,
            "walk" to ActivityKind.WALK,
            "hike" to ActivityKind.HIKE,
            "running" to ActivityKind.RUN,
            "running_(jogging),_6_mph_(10_min_mile)_(myfitnesspal)" to ActivityKind.RUN,
            "kayaking_v2" to ActivityKind.OTHER,
            "indoor_cardio" to ActivityKind.OTHER,
        ).forEach { (type, kind) -> assertEquals(type, kind, ActivityKind.of(type)) }
    }

    companion object {
        /** Google polyline encoder at precision 5, the inverse of [Polyline.decode]. */
        fun encode(points: List<GeoPoint>): String {
            val sb = StringBuilder()
            var pLat = 0L
            var pLon = 0L
            fun put(v: Long) {
                var x = if (v < 0) (v shl 1).inv() else v shl 1
                while (x >= 0x20) {
                    sb.append(((0x20 or (x and 0x1F).toInt()) + 63).toChar())
                    x = x shr 5
                }
                sb.append((x + 63).toInt().toChar())
            }
            for (p in points) {
                val lat = (p.lat * 1e5).roundToLong()
                val lon = (p.lon * 1e5).roundToLong()
                put(lat - pLat)
                put(lon - pLon)
                pLat = lat
                pLon = lon
            }
            return sb.toString()
        }
    }
}
