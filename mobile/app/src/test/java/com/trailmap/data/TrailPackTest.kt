package com.trailmap.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Inside the trail pack's region a load must come from the pack and never reach Overpass —
 * which is the whole point: public mirrors have been measured at 10-45 s or HTTP 504 for the
 * queries a pan makes.
 */
class TrailPackTest {
    @get:Rule val tmp = TemporaryFolder()

    private val kc = GeoPoint(39.0997, -94.5786) // tile -379_156

    private fun way(id: Long, name: String, vararg pts: Pair<Double, Double>) =
        """{"type":"way","id":$id,"tags":{"name":"$name","highway":"cycleway","surface":"asphalt"},""" +
            """"geometry":[${pts.joinToString(",") { """{"lat":${it.first},"lon":${it.second}}""" }}]}"""

    /** Writes a pack the way build_pack.py does, into `<dir>/trailpack/trailpack.zip`. */
    private fun writePack(dir: File, covered: List<String>, tiles: Map<String, List<String>>) {
        val file = File(dir, "trailpack/${TrailPack.FILE_NAME}").apply { parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("meta.json"))
            val meta = """{"schema":${TrailPack.SCHEMA},"tileDeg":0.25,"regions":["kansas","missouri"],""" +
                """"built":"2026-09-26T00:00:00Z","osmTimestamp":"2026-09-25T20:24:36Z",""" +
                """"covered":[${covered.joinToString(",") { "\"$it\"" }}]}"""
            z.write(meta.toByteArray())
            for ((name, elements) in tiles) {
                z.putNextEntry(ZipEntry(name))
                z.write("""{"elements":[${elements.joinToString(",")}]}""".toByteArray())
            }
        }
    }

    /** A mirror that only counts how often it is asked. */
    private fun countingServer(hits: java.util.concurrent.atomic.AtomicInteger): String {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        kotlin.concurrent.thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                hits.incrementAndGet()
                s.close()
            }
        }
        return "http://127.0.0.1:${ss.localPort}/api/interpreter"
    }

    @Test fun loadInsideThePackNeverTouchesTheNetwork() = runBlocking {
        val files = tmp.newFolder("files")
        // One trail spans two tiles and is written to both; one is 20 km out, past the radius.
        val spanning = way(1, "Trolley Track Trail", 39.10 to -94.59, 39.10 to -94.49, 39.10 to -94.45)
        writePack(
            files,
            covered = listOf("-379_156", "-378_156"),
            tiles = mapOf(
                "all/-379_156.json" to listOf(spanning, way(2, "Far Trail", 39.2499 to -94.7499, 39.2490 to -94.7490)),
                "all/-378_156.json" to listOf(spanning),
            ),
        )
        val hits = java.util.concurrent.atomic.AtomicInteger()
        val pack = TrailPack(files)
        assertTrue(pack.covers(kc))
        val client = OverpassClient(cacheDir = tmp.newFolder("cache"), endpoints = listOf(countingServer(hits)), pack = pack)

        val result = client.fetchTrails(kc, 16_000)
        assertEquals(listOf("Trolley Track Trail"), result.trails.map { it.name })
        assertEquals(1, result.trails.single().paths.size) // de-duplicated across the two tiles
        assertEquals(kc, result.servedCenter)
        assertEquals(0, hits.get())
    }

    @Test fun circleCentredOutsideThePackStillUsesTheNetwork() {
        val files = tmp.newFolder("files")
        writePack(files, covered = listOf("-379_156"), tiles = emptyMap())
        val pack = TrailPack(files)
        assertFalse(pack.covers(GeoPoint(41.25, -95.93))) // Omaha
        assertTrue(pack.covers(kc))
    }

    @Test fun wrongSchemaIsIgnored() {
        val files = tmp.newFolder("files")
        val file = File(files, "trailpack/${TrailPack.FILE_NAME}").apply { parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("meta.json"))
            z.write("""{"schema":999,"covered":["-379_156"]}""".toByteArray())
        }
        val pack = TrailPack(files)
        assertFalse(pack.ready)
        assertFalse(pack.covers(kc))
    }

    @Test fun truncatedDownloadNeverReplacesAWorkingPack() {
        val files = tmp.newFolder("files")
        writePack(files, covered = listOf("-379_156"), tiles = emptyMap())
        val pack = TrailPack(files)
        val part = pack.partFile().apply { writeText("not a zip") }
        runCatching { pack.install(part, TrailPack.Installed("x", 9, 0)) }
        assertTrue(pack.ready)
        assertTrue(pack.covers(kc))
    }
}
