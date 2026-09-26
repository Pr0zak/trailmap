package com.trailmap.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The myvitals client against a local stand-in server, and what trailmap makes of the data.
 * The responses are made up in the shape myvitals serves; no real activity is in here.
 */
class MyVitalsTest {
    private val sockets = mutableListOf<ServerSocket>()
    private val paths = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** A tiny HTTP server: [routes] maps a path (with query) to (status, body). */
    private fun server(token: String = "t0ken", routes: Map<String, Pair<Int, String>>): String {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        sockets += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        sock.use { c ->
                            val input = c.getInputStream().bufferedReader()
                            val requestLine = input.readLine() ?: return@use
                            var auth = ""
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Authorization:", ignoreCase = true)) auth = line.substringAfter(":").trim()
                            }
                            val path = requestLine.split(" ")[1]
                            paths += path
                            val (status, body) = when {
                                auth != "Bearer $token" -> 401 to """{"detail":"invalid token"}"""
                                else -> routes[path] ?: (200 to "<!doctype html><html>dashboard</html>")
                            }
                            c.getOutputStream().write(
                                ("HTTP/1.1 $status X\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                            )
                        }
                    }
                }
            }
        }
        return "127.0.0.1:${ss.localPort}"
    }

    @After fun stop() = sockets.forEach { runCatching { it.close() } }

    private val mapBody = """
        {"tracks":[
          {"source":"strava","source_id":"11","type":"ebikeride","name":"Evening Ride",
           "start_at":"2026-09-21T23:10:05Z","duration_s":3600,"distance_m":14000.0,
           "trail_id":null,"trail_name":null,"polyline":"_p~iF~ps|U_ulLnnqC"},
          {"source":"garmin","source_id":"22","type":"walking","name":null,
           "start_at":"2024-05-02T14:00:00+00:00","duration_s":2400,"distance_m":3000.0,
           "polyline":"_p~iF~ps|U_ulLnnqC"},
          {"source":"fitbit","source_id":"33","type":"walk","start_at":"2024-05-03T14:00:00",
           "duration_s":10,"polyline":""},
          {"source":"strava","source_id":"44","type":"ebikeride","name":"20260921-221311",
           "start_at":"2026-09-21T22:13:11Z","duration_s":60,"polyline":"_p~iF~ps|U_ulLnnqC"}
        ],"bounds":null,"primary_bounds":null,"returned":3,"source_points":0,"simplified_points":4}
    """.trimIndent()

    private val trailsBody = """
        {"count":2,"trails":[
          {"id":1,"extension":3,"name":"Swope Park","latitude":38.99,"longitude":-94.51,
           "status":"closed","comment":"Rain","source_ts":"2026-09-25T13:00:00Z",
           "fetched_at":"2026-09-25T13:05:00Z","last_seen_at":"2026-09-26T15:00:00Z",
           "rainout_url":"https://rainoutline.com/search/extension/0000000000/3"},
          {"id":2,"extension":4,"name":"Kessler Park","latitude":null,"longitude":null,
           "status":null,"comment":"","source_ts":null,"fetched_at":null}
        ],"dnis_url":null}
    """.trimIndent()

    @Test fun connectsToTheBackendDirectly() = runBlocking {
        val host = server(routes = mapOf("/activities/map?limit=5000" to (200 to mapBody)))
        val (base, tracks) = MyVitalsClient().connect(host, "t0ken")
        assertEquals("http://$host", base)
        // The track without a polyline is dropped.
        assertEquals(listOf("strava:11", "garmin:22", "strava:44"), tracks.map { it.id })
        // A timestamp is not a name.
        assertEquals(null, tracks[2].name)
        assertEquals("Evening Ride", tracks[0].name)
        val ride = tracks[0]
        assertEquals(ActivityKind.EBIKE, ride.kind)
        assertEquals(parseInstant("2026-09-21T23:10:05Z"), ride.start)
        assertEquals(14000.0 / 1609.344, ride.mph!!, 1e-9)
        assertEquals(2, Polyline.decode(ride.polyline).size)
    }

    @Test fun findsTheApiBehindTheDashboardAddress() = runBlocking {
        // The dashboard answers every path with its web page; the API lives under /api.
        val host = server(routes = mapOf("/api/activities/map?limit=5000" to (200 to mapBody)))
        val (base, tracks) = MyVitalsClient().connect("http://$host/", "t0ken")
        assertEquals("http://$host/api", base)
        assertEquals(3, tracks.size)
    }

    @Test fun wrongTokenSaysSoAndStopsLooking() = runBlocking {
        val host = server(routes = mapOf("/activities/map?limit=5000" to (200 to mapBody)))
        val e = runCatching { MyVitalsClient().connect(host, "nope") }.exceptionOrNull()
        assertTrue("$e", e is MyVitalsException && e.reason == MyVitalsException.Reason.AUTH)
        assertEquals(1, paths.size)
    }

    @Test fun somethingElseIsNotMyVitals() = runBlocking {
        val host = server(routes = emptyMap())
        val e = runCatching { MyVitalsClient().connect(host, "t0ken") }.exceptionOrNull()
        assertTrue("$e", e is MyVitalsException && e.reason == MyVitalsException.Reason.NOT_MYVITALS)
    }

    @Test fun nobodyListeningIsUnreachable() = runBlocking {
        val e = runCatching { MyVitalsClient().connect("127.0.0.1:1", "t0ken") }.exceptionOrNull()
        assertTrue("$e", e is MyVitalsException && e.reason == MyVitalsException.Reason.UNREACHABLE)
    }

    @Test fun aKeyWithCharactersAHeaderCantCarrySaysSo() = runBlocking {
        val host = server(routes = mapOf("/activities/map?limit=5000" to (200 to mapBody)))
        for (bad in listOf("t0k\u00E9n", "t0ken\u2028", "t0\u200Bken")) {
            val e = runCatching { MyVitalsClient().connect(host, bad) }.exceptionOrNull()
            // The key, not the address, and no second try with /api.
            assertTrue("$e", e is MyVitalsException && e.reason == MyVitalsException.Reason.AUTH)
            assertEquals(MyVitalsClient.BAD_KEY_TEXT, e!!.message)
        }
        // Nothing was sent: OkHttp refuses the header before any request goes out.
        assertTrue(paths.isEmpty())
    }

    @Test fun aBadAddressStillSaysAddress() = runBlocking {
        val e = runCatching { MyVitalsClient().connect("http://my vitals.local", "t0ken") }.exceptionOrNull()
        assertTrue("$e", e is MyVitalsException && e.reason == MyVitalsException.Reason.NOT_MYVITALS)
        assertEquals("That isn't a web address.", e!!.message)
    }

    @Test fun pasteTakesTheKeyAndSaysWhyNot() {
        fun key(text: String?) = (PastedKey.of(text) as? PastedKey.Key)?.key
        fun why(text: String?) = (PastedKey.of(text) as? PastedKey.Rejected)?.message

        assertEquals("t0ken", key("t0ken"))
        // A copied key's stray ends go.
        assertEquals("t0ken", key("  t0ken\n"))
        assertEquals("t0ken", key("\tt0ken\r\n"))

        assertEquals("Nothing to paste.", why(null))
        assertEquals("Nothing to paste.", why(""))
        assertEquals("Nothing to paste.", why(" \n\t "))
        for (inner in listOf("t0 ken", "t0\nken", "t0\tken", "t0\u00A0ken", "Bearer t0ken")) {
            assertTrue(inner, why(inner)!!.contains("inside"))
        }
        for (odd in listOf("t0k\u00E9n", "t0\u200Bken", "t0\u0000ken", "t0\u007Fken", "t\uD83D\uDEB2ken")) {
            assertEquals(odd, "That has characters an access key can't contain.", why(odd))
        }
        assertNotNull(why("k".repeat(4097)))
        assertEquals("k".repeat(4096), key("k".repeat(4096)))
    }

    @Test fun conditionLinksOpenOnlyWebPages() {
        fun link(url: String?) = TrailStatus(1, "Swope Park", url = url).link
        for (ok in listOf("https://rainoutline.com/search/extension/0000000000/3", "http://rainoutline.com/", "HTTPS://Example.com/x")) {
            assertEquals(ok, link(ok))
        }
        for (bad in listOf(
            null, "", "javascript:alert(1)", "intent://x#Intent;scheme=https;end", "file:///sdcard/x", "content://x/y",
            "market://details?id=x", "data:text/html,hi", " https://rainoutline.com/", "https://rainoutline.com/\n",
            "https://rain outline.com/", "https://", "rainoutline.com/x", "ftp://rainoutline.com/",
        )) {
            assertNull("$bad", link(bad))
        }
    }

    @Test fun readsTrailConditions() = runBlocking {
        val host = server(routes = mapOf("/trails" to (200 to trailsBody)))
        val statuses = MyVitalsClient().conditions("http://$host", "t0ken")
        assertEquals(2, statuses.size)
        val swope = statuses[0]
        assertEquals(TrailCondition.CLOSED, swope.condition)
        assertEquals("Rain", swope.comment)
        assertEquals(parseInstant("2026-09-25T13:00:00Z"), swope.updatedAt)
        assertEquals(parseInstant("2026-09-26T15:00:00Z"), swope.checkedAt)
        assertNotNull(swope.point)
        val kessler = statuses[1]
        assertEquals(TrailCondition.UNKNOWN, kessler.condition)
        assertNull(kessler.comment)
        assertNull(kessler.point)
    }

    @Test fun normalisesAddresses() {
        assertEquals("http://myvitals.lan:8000", MyVitalsClient.normalizeUrl("  myvitals.lan:8000/ "))
        assertEquals("https://h.example", MyVitalsClient.normalizeUrl("https://h.example"))
        assertEquals("", MyVitalsClient.normalizeUrl("  "))
    }

    @Test fun storeRoundTripsAndForgets() {
        val dir = kotlin.io.path.createTempDirectory("myvitals").toFile()
        val store = MyVitalsStore(java.io.File(dir, "myvitals"))
        assertEquals(MyVitalsSettings(), store.settings())
        val s = MyVitalsSettings(url = "http://h:8000", token = "t", apiBase = "http://h:8000", lastSync = 5)
        store.saveSettings(s)
        store.saveTracks(listOf(RecordedTrack("a:1", "ride", null, 1, 60, 100.0, "_p~iF~ps|U_ulLnnqC")))
        assertEquals(s, store.settings())
        assertEquals(1, store.tracks().size)
        assertTrue(store.settings().configured)
        store.clear()
        assertEquals(MyVitalsSettings(), store.settings())
        assertTrue(store.tracks().isEmpty())
    }

    // --- pace ---------------------------------------------------------------------------

    private val now = parseInstant("2026-09-26T12:00:00Z")!!
    private val day = 24 * 3600 * 1000L

    private fun rec(type: String, daysAgo: Int, miles: Double, minutes: Int) =
        RecordedTrack("x:$type$daysAgo$miles", type, null, now - daysAgo * day, minutes * 60, miles * 1609.344, "_p~iF~ps|U_ulLnnqC")

    private fun trailOf(surface: SurfaceType, uses: Set<UseType>, mtb: Int? = null) = Trail(
        "t", "t", surface, mapOf(surface to 1.0), uses, 1000.0, 0.0,
        listOf(listOf(GeoPoint(39.0, -94.6), GeoPoint(39.01, -94.6))), GeoPoint(39.005, -94.6), mtbScale = mtb,
    )

    @Test fun paceFollowsTheBikeYouRideNow() {
        val tracks = (1..4).map { rec("cycling", 900 + it, 10.0, 75) } + // 8 mph, years ago
            (1..5).map { rec("ebikeride", it * 10, 9.0, 60) } +              // 9 mph, this year
            (1..3).map { rec("mountain_biking", it * 20, 5.5, 60) } +
            (1..3).map { rec("walking", it * 5, 3.0, 60) } +
            listOf(rec("walking", 3, 30.0, 60))                              // GPS garbage: 30 mph walk
        val pace = PersonalPace.from(tracks, now)
        assertEquals(8.0, pace.bike!!.mph, 1e-9)
        assertEquals(9.0, pace.ebike!!.mph, 1e-9)
        assertEquals(3.0, pace.walk!!.mph, 1e-9)
        assertEquals(3, pace.walk!!.count)

        val bikePath = trailOf(SurfaceType.PAVED, setOf(UseType.WALK, UseType.BIKE))
        assertEquals(ActivityKind.EBIKE, pace.forTrail(bikePath)!!.kind)
        val singletrack = trailOf(SurfaceType.DIRT, setOf(UseType.BIKE), mtb = 2)
        assertEquals(5.5, pace.forTrail(singletrack)!!.mph, 1e-9)
        val footpath = trailOf(SurfaceType.DIRT, setOf(UseType.WALK))
        assertEquals(ActivityKind.WALK, pace.forTrail(footpath)!!.kind)
    }

    @Test fun noRecordingsNoPace() {
        val pace = PersonalPace.from(emptyList(), now)
        assertNull(pace.forTrail(trailOf(SurfaceType.PAVED, setOf(UseType.BIKE))))
    }

    // --- trail conditions ---------------------------------------------------------------

    private fun status(id: Long, name: String, lat: Double?, lon: Double?, s: String = "closed") =
        TrailStatus(id, name, lat, lon, s)

    private fun mtbTrail(name: String, park: String?, lat: Double, lon: Double, surface: SurfaceType = SurfaceType.DIRT) = Trail(
        name, name, surface, mapOf(surface to 1.0), setOf(UseType.BIKE), 1000.0, 0.0,
        listOf(listOf(GeoPoint(lat, lon), GeoPoint(lat + 0.005, lon))), GeoPoint(lat + 0.0025, lon), mtbScale = 2, parkName = park,
    )

    @Test fun conditionsMatchSystemsByNameThenDistance() {
        val board = listOf(
            status(1, "Shawnee Mission Park North Trails - Bike, Hike", 39.0, -94.80),
            status(2, "Shawnee Mission Park South Hiking Trails", 38.98, -94.79),
            status(3, "Swope Park", 38.99, -94.51),
            status(4, "Landahl Park", 39.02, -94.30),
        )
        fun system(name: String, vararg trails: Trail) =
            clusterTrailSystems(trails.toList()).single().copy(name = name)

        // Both Shawnee boards match the name; the nearer trailhead wins.
        val smp = system("Shawnee Mission Park", mtbTrail("a", "Shawnee Mission Park", 39.001, -94.801))
        assertEquals(1L, TrailConditions.forSystem(smp, board)!!.id)
        // No name to go on: the trailhead a few hundred metres away.
        val unnamed = system("Blue Loop", mtbTrail("b", null, 38.992, -94.512))
        assertEquals(3L, TrailConditions.forSystem(unnamed, board)!!.id)
        // Nothing nearby, no name match.
        val far = system("Elsewhere", mtbTrail("c", null, 40.0, -95.0))
        assertNull(TrailConditions.forSystem(far, board))
    }

    @Test fun pavedTrailsDontCloseForRain() {
        val board = listOf(status(3, "Swope Park", 38.99, -94.51))
        val greenway = mtbTrail("g", "Swope Park", 38.991, -94.511, surface = SurfaceType.PAVED).copy(mtbScale = null)
        assertNull(TrailConditions.forTrail(greenway, board))
        val single = mtbTrail("s", "Swope Park", 38.991, -94.511)
        assertEquals(3L, TrailConditions.forTrail(single, board)!!.id)
    }
}
