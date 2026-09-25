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
}
