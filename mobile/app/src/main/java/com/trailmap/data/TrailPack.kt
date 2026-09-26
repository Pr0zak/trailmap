package com.trailmap.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.floor

/**
 * The regional trail pack: every trail the app's Overpass queries would find in a region,
 * pre-extracted from OpenStreetMap by `trailpack/build_pack.py` (weekly, in GitHub Actions) and
 * downloaded once. Inside the region, loads read it from disk instead of asking a public
 * Overpass server — which has been measured taking 10-45 s, or refusing outright with HTTP 504,
 * on the queries the map makes every time it is panned.
 *
 * The file is a zip of 0.25° tiles, each holding Overpass `out geom` JSON for one query kind
 * (`all`, `mtb`, `parks`), so [OverpassClient] parses it with the same code as a network answer.
 * A tile is listed in [Meta.covered] only if the pack's regions contain it outright; a circle
 * centred anywhere else still goes to the network.
 */
class TrailPack(dir: File) {
    private val dir = File(dir, "trailpack")
    val file = File(this.dir, FILE_NAME)
    private val sidecar = File(this.dir, "installed.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Meta(
        val schema: Int = 0,
        val tileDeg: Double = 0.25,
        val regions: List<String> = emptyList(),
        val built: String = "",
        val osmTimestamp: String? = null,
        val covered: Set<String> = emptySet(),
    )

    /** What was installed, as the release described it — compared on the next update check. */
    @Serializable
    data class Installed(val updatedAt: String = "", val size: Long = 0, val checkedAt: Long = 0)

    private var zip: ZipFile? = null
    private val _meta = MutableStateFlow<Meta?>(null)

    /** The installed pack, or null. Changes when a download lands or the pack is removed. */
    val meta: StateFlow<Meta?> = _meta.asStateFlow()

    init {
        synchronized(this) { open() }
    }

    private fun open() {
        zip?.runCatching { close() }
        zip = null
        _meta.value = null
        if (!file.exists()) return
        runCatching {
            val z = ZipFile(file)
            val m = json.decodeFromString<Meta>(z.getInputStream(z.getEntry("meta.json")).readBytes().decodeToString())
            if (m.schema != SCHEMA) {
                z.close()
                DiagLog.log("pack", "installed pack is schema ${m.schema}, app reads $SCHEMA — ignoring it")
                return
            }
            zip = z
            _meta.value = m
            DiagLog.log("pack", "open: ${m.regions.joinToString("+")}, ${m.covered.size} tiles, OSM data ${m.osmTimestamp}")
        }.onFailure { DiagLog.log("pack", "unreadable pack (${it.message}); ignoring it") }
    }

    fun installed(): Installed? =
        runCatching { json.decodeFromString<Installed>(sidecar.readText()) }.getOrNull()

    fun recordCheck(installed: Installed) {
        dir.mkdirs()
        sidecar.writeText(json.encodeToString(Installed.serializer(), installed))
    }

    /** A pack of this app's schema is installed. */
    val ready: Boolean get() = _meta.value != null

    /** Where a download is written before [install] swaps it in. */
    fun partFile(): File = File(dir.apply { mkdirs() }, "$FILE_NAME.part")

    /**
     * Swap a finished download in. It is checked first — it has to open as a zip and carry a
     * meta.json of this schema — so a truncated or wrong file never replaces a working pack.
     */
    @Synchronized
    fun install(part: File, installed: Installed) {
        ZipFile(part).use { z ->
            val m = json.decodeFromString<Meta>(z.getInputStream(z.getEntry("meta.json")).readBytes().decodeToString())
            check(m.schema == SCHEMA) { "pack schema ${m.schema}, app reads $SCHEMA" }
        }
        zip?.runCatching { close() }
        zip = null
        if (!part.renameTo(file)) {
            part.copyTo(file, overwrite = true)
            part.delete()
        }
        recordCheck(installed)
        open()
    }

    @Synchronized
    fun remove() {
        zip?.runCatching { close() }
        zip = null
        file.delete()
        sidecar.delete()
        _meta.value = null
    }

    fun bytes(): Long = if (file.exists()) file.length() else 0L

    /** True when the pack holds everything around [center]. */
    fun covers(center: GeoPoint): Boolean {
        val m = _meta.value ?: return false
        return tileKey(center, m.tileDeg) in m.covered
    }

    /** Tiles a circle touches. */
    fun tilesFor(center: GeoPoint, radiusMeters: Int): List<Pair<Int, Int>> {
        val deg = _meta.value?.tileDeg ?: return emptyList()
        val dLat = radiusMeters / 111_320.0
        val dLon = dLat / cos(Math.toRadians(center.lat)).coerceAtLeast(0.1)
        val x0 = floor((center.lon - dLon) / deg).toInt()
        val x1 = floor((center.lon + dLon) / deg).toInt()
        val y0 = floor((center.lat - dLat) / deg).toInt()
        val y1 = floor((center.lat + dLat) / deg).toInt()
        return (x0..x1).flatMap { x -> (y0..y1).map { y -> x to y } }
    }

    /** One tile's Overpass JSON, or null if the region has nothing of this kind there. */
    @Synchronized
    fun tile(kind: String, x: Int, y: Int): String? {
        val z = zip ?: return null
        val entry = z.getEntry("$kind/${x}_$y.json") ?: return null
        return z.getInputStream(entry).use { it.readBytes().decodeToString() }
    }

    /** Distinguishes tiles of one installed pack from the next in the parse cache. */
    val version: String get() = _meta.value?.built ?: ""

    private fun tileKey(p: GeoPoint, deg: Double) =
        "${floor(p.lon / deg).toInt()}_${floor(p.lat / deg).toInt()}"

    companion object {
        /** Must match PACK_SCHEMA in trailpack/build_pack.py. */
        const val SCHEMA = 1
        const val FILE_NAME = "trailpack.zip"
    }
}
