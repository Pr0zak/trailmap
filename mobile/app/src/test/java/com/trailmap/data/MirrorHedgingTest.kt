package com.trailmap.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A stalled mirror must not sink the load: after [OverpassClient.HEDGE_AFTER_MS] the next
 * mirror is asked in parallel and its answer is used.
 */
class MirrorHedgingTest {
    private val sockets = mutableListOf<ServerSocket>()
    private val hits = java.util.concurrent.atomic.AtomicInteger()

    /** A one-route HTTP/1.1 server that answers every POST with an empty result after [delayMs]. */
    private fun server(delayMs: Long): String {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        sockets += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        sock.use { c ->
                            val input = c.getInputStream().bufferedReader()
                            var length = 0
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                    length = line.substringAfter(":").trim().toInt()
                                }
                            }
                            repeat(length) { input.read() }
                            hits.incrementAndGet()
                            Thread.sleep(delayMs)
                            val body = """{"elements":[]}"""
                            c.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                            )
                        }
                    }
                }
            }
        }
        return "http://127.0.0.1:${ss.localPort}/api/interpreter"
    }

    @After fun stop() = sockets.forEach { runCatching { it.close() } }

    @Test fun stalledPreferredMirrorIsOvertakenByTheNext() = runBlocking {
        val stalled = server(delayMs = 40_000)
        val healthy = server(delayMs = 200)
        val client = OverpassClient(endpoints = listOf(stalled, healthy))
        val t = System.currentTimeMillis()
        client.fetchTrails(GeoPoint(39.0997, -94.5786), 16000)
        val took = System.currentTimeMillis() - t
        val log = DiagLog.snapshot()
        assertTrue("took $took ms", took in OverpassClient.HEDGE_AFTER_MS..(OverpassClient.HEDGE_AFTER_MS + 5_000))
        assertTrue(log.joinToString("\n"), log.any { "in 8 s, also asking 127.0.0.1" in it })
        assertTrue(log.joinToString("\n"), log.any { "dropped, 127.0.0.1 answered first" in it })
    }

    @Test fun failingMirrorHandsOverImmediately() = runBlocking {
        val dead = "http://127.0.0.1:1/api/interpreter" // nothing listens on port 1
        val healthy = server(delayMs = 100)
        val client = OverpassClient(endpoints = listOf(dead, healthy))
        val t = System.currentTimeMillis()
        client.fetchTrails(GeoPoint(39.0997, -94.5786), 16000)
        val took = System.currentTimeMillis() - t
        assertTrue("took $took ms", took < 3_000)
    }

    @Test fun mtbTrailsArriveBeforeParkNames() = runBlocking {
        // A cache directory, as the app always has: the trail response is kept there, so the
        // follow-up call reads it back instead of asking again.
        val cache = kotlin.io.path.createTempDirectory("trailmap-cache").toFile()
        val client = OverpassClient(cacheDir = cache, endpoints = listOf(server(delayMs = 50)))
        val center = GeoPoint(39.0997, -94.5786)
        val first = client.fetchTrails(center, 40233, mtb = true, withParks = false)
        assertTrue("parks should be pending", first.parksPending)
        assertTrue("one request for trails, got ${hits.get()}", hits.get() == 1)
        val second = client.fetchTrails(center, 40233, mtb = true)
        assertTrue("parks should be in", !second.parksPending)
        // Trails come from the in-memory parse cache; only the park query goes out.
        assertTrue("one more request for parks, got ${hits.get()}", hits.get() == 2)
    }
}
