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
 * Inside the installed states a load must come from their packs and never reach Overpass —
 * which is the whole point: public mirrors have been measured at 10-45 s or HTTP 504 for the
 * queries a pan makes. Coverage is decided across states together, because a tile on a state
 * line (downtown Kansas City's) belongs to neither state alone.
 */
class TrailPackTest {
    @get:Rule val tmp = TemporaryFolder()

    private val kc = GeoPoint(39.0997, -94.5786) // tile -379_156: lon -94.75..-94.5, on the state line

    // Rectangles standing in for the states' padded outlines; they overlap at the line, as
    // Geofabrik's do.
    private val kansas = listOf(-102.1 to 36.9, -94.6 to 36.9, -94.6 to 40.1, -102.1 to 40.1)
    private val missouri = listOf(-94.62 to 35.9, -89.0 to 35.9, -89.0 to 40.7, -94.62 to 40.7)

    private fun way(id: Long, name: String, vararg pts: Pair<Double, Double>) =
        """{"type":"way","id":$id,"tags":{"name":"$name","highway":"cycleway","surface":"asphalt"},""" +
            """"geometry":[${pts.joinToString(",") { """{"lat":${it.first},"lon":${it.second}}""" }}]}"""

    /** Writes a pack the way build_pack.py does. */
    private fun pack(
        file: File,
        regions: List<String>,
        outline: List<Pair<Double, Double>>? = null,
        covered: List<String> = emptyList(),
        tiles: Map<String, List<String>> = emptyMap(),
        schema: Int = TrailPacks.SCHEMA,
    ): File {
        file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { z ->
            val ring = outline?.let { o -> "[[" + (o + o.first()).joinToString(",") { "[${it.first},${it.second}]" } + "]]" } ?: "[]"
            val meta = """{"schema":$schema,"tileDeg":0.25,"regions":[${regions.joinToString(",") { "\"$it\"" }}],""" +
                """"built":"2026-09-26T00:00:00Z","osmTimestamp":"2026-09-25T20:24:36Z",""" +
                """"covered":[${covered.joinToString(",") { "\"$it\"" }}],"outline":$ring}"""
            z.putNextEntry(ZipEntry("meta.json"))
            z.write(meta.toByteArray())
            for ((name, elements) in tiles) {
                z.putNextEntry(ZipEntry(name))
                z.write("""{"elements":[${elements.joinToString(",")}]}""".toByteArray())
            }
        }
        return file
    }

    private fun stateFile(files: File, slug: String) = File(files, "trailpack/state-$slug.zip")

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

    @Test fun loadInsideThePacksNeverTouchesTheNetwork() = runBlocking {
        val files = tmp.newFolder("files")
        // A trail crossing the state line is in both states' extracts; one is 20 km out.
        val crossing = way(1, "Trolley Track Trail", 39.10 to -94.65, 39.10 to -94.55)
        pack(stateFile(files, "kansas"), listOf("kansas"), kansas,
            tiles = mapOf("all/-379_156.json" to listOf(crossing, way(2, "Far Trail", 39.2499 to -94.7499, 39.2490 to -94.7490))))
        pack(stateFile(files, "missouri"), listOf("missouri"), missouri,
            tiles = mapOf("all/-379_156.json" to listOf(crossing)))
        val packs = TrailPacks(files)
        assertTrue(packs.covers(kc))

        val hits = java.util.concurrent.atomic.AtomicInteger()
        val client = OverpassClient(cacheDir = tmp.newFolder("cache"), endpoints = listOf(countingServer(hits)), pack = packs)
        val result = client.fetchTrails(kc, 16_000)
        assertEquals(listOf("Trolley Track Trail"), result.trails.map { it.name })
        assertEquals(1, result.trails.single().paths.size) // de-duplicated across the two states
        assertEquals(0, hits.get())
    }

    @Test fun borderTileNeedsBothStates() {
        val files = tmp.newFolder("files")
        pack(stateFile(files, "kansas"), listOf("kansas"), kansas)
        assertFalse("Kansas alone doesn't cover downtown KC", TrailPacks(files).covers(kc))
        assertTrue("but it covers Lawrence", TrailPacks(files).covers(GeoPoint(38.97, -95.24)))
        pack(stateFile(files, "missouri"), listOf("missouri"), missouri)
        assertTrue(TrailPacks(files).covers(kc))
        assertFalse(TrailPacks(files).covers(GeoPoint(41.25, -95.93))) // Omaha: neither
    }

    @Test fun combinedPackFromOlderAppBecomesTheSelectionThenRetires() {
        val files = tmp.newFolder("files")
        pack(File(files, "trailpack/trailpack.zip"), listOf("kansas", "missouri"), covered = listOf("-379_156"))
        val packs = TrailPacks(files)
        assertEquals(setOf("kansas", "missouri"), packs.selected.value)
        assertTrue(packs.covers(kc))

        packs.install("kansas", pack(packs.partFile("kansas"), listOf("kansas"), kansas), TrailPacks.Installed("a", 1))
        packs.retireLegacyIfReplaced()
        assertTrue("kept until every state has its own pack", File(files, "trailpack/trailpack.zip").exists())
        packs.install("missouri", pack(packs.partFile("missouri"), listOf("missouri"), missouri), TrailPacks.Installed("b", 1))
        packs.retireLegacyIfReplaced()
        assertFalse(File(files, "trailpack/trailpack.zip").exists())
        assertTrue(packs.covers(kc))
    }

    @Test fun removingAStateDeletesItsPack() {
        val files = tmp.newFolder("files")
        pack(stateFile(files, "kansas"), listOf("kansas"), kansas)
        pack(stateFile(files, "missouri"), listOf("missouri"), missouri)
        val packs = TrailPacks(files)
        packs.setSelected(setOf("kansas", "missouri"))
        assertTrue(packs.covers(kc))

        packs.deselect("kansas")
        assertFalse(stateFile(files, "kansas").exists())
        assertTrue(stateFile(files, "missouri").exists())
        assertFalse(packs.covers(kc))
        assertEquals(setOf("missouri"), TrailPacks(files).selected.value) // persisted
    }

    @Test fun truncatedDownloadNeverReplacesAWorkingPack() {
        val files = tmp.newFolder("files")
        pack(stateFile(files, "kansas"), listOf("kansas"), kansas)
        val packs = TrailPacks(files)
        val part = packs.partFile("kansas").apply { writeText("not a zip") }
        runCatching { packs.install("kansas", part, TrailPacks.Installed("x", 9)) }
        assertTrue(packs.hasState("kansas"))
        assertTrue(packs.covers(GeoPoint(38.97, -95.24)))
    }

    @Test fun statesAroundKansasCityAreBothSidesOfTheLine() {
        val files = tmp.newFolder("files")
        val packs = TrailPacks(files)
        fun entry(slug: String, ring: List<Pair<Double, Double>>) =
            """{"slug":"$slug","name":"${slug.replaceFirstChar { it.uppercase() }}","bytes":1,""" +
                """"outline":[[${(ring + ring.first()).joinToString(",") { "[${it.first},${it.second}]" }}]]}"""
        packs.saveIndex("""{"schema":1,"states":[${entry("kansas", kansas)},${entry("missouri", missouri)}]}""", "t")
        // Downtown KC is in Missouri, but its tile needs Kansas too.
        assertEquals(setOf("missouri"), packs.statesAt(kc).map { it.slug }.toSet())
        assertEquals(setOf("kansas", "missouri"), packs.statesAround(kc, 25_000.0).map { it.slug }.toSet())
        assertEquals(setOf("kansas"), packs.statesAround(GeoPoint(38.97, -95.24), 25_000.0).map { it.slug }.toSet())
        // An offline map of the KC metro box needs both states' trails.
        assertEquals(setOf("kansas", "missouri"), packs.statesIn(-94.80, 38.80, -94.30, 39.40).map { it.slug }.toSet())
        assertEquals(setOf("kansas"), packs.statesIn(-95.30, 38.90, -95.15, 38.99).map { it.slug }.toSet()) // Lawrence
    }

    /**
     * Schema 2 stores each element once: a small one in the tile holding its centre, a wide one
     * (a long-distance route) in wide.json. A circle must still find both.
     */
    @Test fun elementsStoredOnceAreStillFound() = runBlocking {
        val files = tmp.newFolder("files")
        // Centred in the tile north of KC's (-379_157) but reaching down to 39.20, inside a
        // 16 km circle around downtown; and a 3° route stored as wide.
        val neighbour = way(3, "Line Creek Trail", 39.20 to -94.60, 39.30 to -94.60)
        val route = way(4, "Katy Trail", 38.0 to -91.0, 39.12 to -94.57)
        pack(stateFile(files, "missouri"), listOf("missouri"), missouri,
            tiles = mapOf("all/-379_157.json" to listOf(neighbour), "all/wide.json" to listOf(route)))
        pack(stateFile(files, "kansas"), listOf("kansas"), kansas)
        val client = OverpassClient(cacheDir = tmp.newFolder("cache"), endpoints = emptyList(), pack = TrailPacks(files))
        val names = client.fetchTrails(kc, 16_000).trails.map { it.name }.toSet()
        assertEquals(setOf("Line Creek Trail", "Katy Trail"), names)
    }

    @Test fun wrongSchemaIsIgnored() {
        val files = tmp.newFolder("files")
        pack(stateFile(files, "kansas"), listOf("kansas"), kansas, schema = 999)
        val packs = TrailPacks(files)
        assertFalse(packs.ready)
        assertFalse(packs.covers(GeoPoint(38.97, -95.24)))
    }
}
