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
 * Trail packs: every trail the app's Overpass queries would find in a US state, pre-extracted
 * from OpenStreetMap by `trailpack/build_pack.py` (weekly, in GitHub Actions) and downloaded for
 * the states the user picks. Inside them, loads read the phone's storage instead of asking a
 * public Overpass server — measured at 10-45 s, or refusing outright with HTTP 504, for the
 * queries the map makes every time it is panned.
 *
 * Each pack is a zip of 0.25° tiles holding Overpass `out geom` JSON for one query kind (`all`,
 * `mtb`, `parks`), so [OverpassClient] parses it with the same code as a network answer. Since
 * schema 2 each element is stored once: in the tile holding its centre, or — if it is wider than
 * a tile, like the Arizona Trail or a national forest — in `<kind>/wide.json`. So a circle reads
 * its tiles plus one ring, and the wide file of every state it reaches. Schema 1 (every tile an
 * element touched; the combined pack 0.15.0 downloaded) reads correctly the same way.
 *
 * Coverage is decided across every installed state together. Kansas City's downtown tile is
 * inside neither Kansas nor Missouri alone — the state line runs through it — so a tile counts
 * as covered when the installed states' outlines, between them, contain it. A circle centred
 * anywhere else still goes to the network.
 *
 * Files live in `filesDir/trailpack`: `state-<slug>.zip` per state, plus `trailpack.zip`, the
 * combined Kansas + Missouri pack 0.15.0 downloaded, which is used until its states have been
 * downloaded individually and then deleted.
 */
class TrailPacks(filesDir: File) {
    private val dir = File(filesDir, "trailpack")
    private val storeFile = File(dir, "packs.json")
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Meta(
        val schema: Int = 0,
        val tileDeg: Double = TILE_DEG,
        val regions: List<String> = emptyList(),
        val built: String = "",
        val osmTimestamp: String? = null,
        val covered: Set<String> = emptySet(),
        /** Rings of [lon, lat], padded to contain the whole region. */
        val outline: List<List<List<Double>>> = emptyList(),
    )

    /** What was installed, as the release described it — compared on the next update check. */
    @Serializable
    data class Installed(val updatedAt: String = "", val size: Long = 0)

    /** A state the release offers, from `trailpack-index.json`. */
    @Serializable
    data class StateEntry(
        val slug: String,
        val name: String = slug,
        val asset: String = "",
        val bytes: Long = 0,
        val built: String = "",
        val osmTimestamp: String? = null,
        val bbox: List<Double> = emptyList(),
        /** Coarse rings of [lon, lat]: only for "which state is the map looking at?". */
        val outline: List<List<List<Double>>> = emptyList(),
    ) {
        @kotlinx.serialization.Transient
        private val rings: List<Ring> = outline.map(::Ring)

        fun contains(p: GeoPoint): Boolean = rings.any { it.contains(p.lon, p.lat) }

        /** Within [deg] of the state's bounding box — a cheap "next door" test. */
        fun near(p: GeoPoint, deg: Double): Boolean = bbox.size == 4 &&
            p.lon >= bbox[0] - deg && p.lat >= bbox[1] - deg && p.lon <= bbox[2] + deg && p.lat <= bbox[3] + deg
    }

    @Serializable
    data class Index(val schema: Int = 0, val generated: String = "", val states: List<StateEntry> = emptyList())

    @Serializable
    private data class Store(
        /** The states the user wants on the phone; null until anything has been chosen. */
        val selected: Set<String>? = null,
        val installed: Map<String, Installed> = emptyMap(),
        val checkedAt: Long = 0,
        val indexUpdatedAt: String = "",
    )

    /** One pack on disk, for the UI. */
    data class Info(
        val id: String,
        val regions: List<String>,
        val bytes: Long,
        val built: String,
        val osmTimestamp: String?,
    )

    /** A ray-cast polygon ring with a bounding-box shortcut. */
    private class Ring(points: List<List<Double>>) {
        private val xs = DoubleArray(points.size) { points[it][0] }
        private val ys = DoubleArray(points.size) { points[it][1] }
        private val minX = xs.minOrNull() ?: 0.0
        private val maxX = xs.maxOrNull() ?: 0.0
        private val minY = ys.minOrNull() ?: 0.0
        private val maxY = ys.maxOrNull() ?: 0.0

        fun contains(x: Double, y: Double): Boolean {
            if (xs.size < 3 || x < minX || x > maxX || y < minY || y > maxY) return false
            var inside = false
            var j = xs.size - 1
            for (i in xs.indices) {
                if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside
                j = i
            }
            return inside
        }
    }

    private class Pack(val id: String, val file: File, val zip: ZipFile, val meta: Meta) {
        val rings = meta.outline.map(::Ring)
        val bytes = file.length()

        /** West, south, east, north: from the outline, or the covered tiles when there is none. */
        val bbox: DoubleArray = run {
            val pts = meta.outline.flatten()
            if (pts.isNotEmpty()) {
                doubleArrayOf(pts.minOf { it[0] }, pts.minOf { it[1] }, pts.maxOf { it[0] }, pts.maxOf { it[1] })
            } else {
                val xy = meta.covered.mapNotNull { k -> k.split('_').takeIf { it.size == 2 }?.map { it.toInt() } }
                if (xy.isEmpty()) doubleArrayOf(0.0, 0.0, 0.0, 0.0) else doubleArrayOf(
                    xy.minOf { it[0] } * TILE_DEG, xy.minOf { it[1] } * TILE_DEG,
                    (xy.maxOf { it[0] } + 1) * TILE_DEG, (xy.maxOf { it[1] } + 1) * TILE_DEG,
                )
            }
        }
        fun contains(lon: Double, lat: Double) = rings.any { it.contains(lon, lat) }
        fun info() = Info(id, meta.regions, bytes, meta.built, meta.osmTimestamp)
    }

    /** A tile of one pack: [key] identifies it in the parse cache, [read] loads its JSON. */
    class TileSource internal constructor(val key: String, private val zip: ZipFile, private val name: String) {
        /** Null if the pack was swapped or removed mid-read; the tile is simply skipped. */
        fun read(): String? = runCatching {
            zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } }
        }.getOrNull()
    }

    @Volatile private var packs: Map<String, Pack> = emptyMap()
    private var store = Store()
    private val coverCache = HashMap<Long, Boolean>()

    private val _installed = MutableStateFlow<List<Info>>(emptyList())
    /** Packs on disk. Changes when a download lands or a state is removed. */
    val installed: StateFlow<List<Info>> = _installed.asStateFlow()

    private val _index = MutableStateFlow<Index?>(null)
    /** The states the release offers; null until the index has been downloaded once. */
    val index: StateFlow<Index?> = _index.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    /** The states the user wants on the phone. */
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    init {
        synchronized(this) {
            dir.mkdirs()
            store = runCatching { json.decodeFromString<Store>(storeFile.readText()) }.getOrDefault(Store())
            _index.value = runCatching { json.decodeFromString<Index>(indexFile.readText()) }.getOrNull()
            reload()
            // 0.15.0 downloaded Kansas + Missouri as one file. Carry its states over as the
            // selection, so they are fetched individually and it can then be retired.
            if (store.selected == null) packs[LEGACY]?.let { saveStore(store.copy(selected = it.meta.regions.toSet())) }
            _selected.value = store.selected.orEmpty()
        }
    }

    private fun saveStore(s: Store) {
        store = s
        dir.mkdirs()
        storeFile.writeText(json.encodeToString(Store.serializer(), s))
        _selected.value = s.selected.orEmpty()
    }

    private fun open(id: String, file: File): Pack? = runCatching {
        val z = ZipFile(file)
        val meta = runCatching {
            json.decodeFromString<Meta>(z.getInputStream(z.getEntry("meta.json")).readBytes().decodeToString())
        }.getOrElse { z.close(); throw it }
        if (meta.schema !in SCHEMAS || meta.tileDeg != TILE_DEG) {
            z.close()
            DiagLog.log("pack", "$id is schema ${meta.schema}, app reads $SCHEMAS — ignoring it")
            return null
        }
        Pack(id, file, z, meta)
    }.getOrElse {
        DiagLog.log("pack", "unreadable pack $id (${it.message}); ignoring it")
        null
    }

    private fun reload() {
        val found = LinkedHashMap<String, Pack>()
        File(dir, LEGACY_FILE).takeIf { it.exists() }?.let { f -> open(LEGACY, f)?.let { found[LEGACY] = it } }
        dir.listFiles { f -> f.name.startsWith("state-") && f.name.endsWith(".zip") }?.sortedBy { it.name }?.forEach { f ->
            val id = f.name.removePrefix("state-").removeSuffix(".zip")
            open(id, f)?.let { found[id] = it }
        }
        publish(found)
        if (found.isNotEmpty()) DiagLog.log("pack", "open: ${found.keys.joinToString()}")
    }

    /**
     * Swap in a new set of packs. Only the packs actually replaced or removed are closed by the
     * callers — closing every zip would pull the tiles out from under a load that is reading a
     * state the change had nothing to do with.
     */
    private fun publish(next: Map<String, Pack>) {
        packs = next
        synchronized(coverCache) { coverCache.clear() }
        _installed.value = next.values.map { it.info() }
    }

    /** Close and delete one pack. */
    private fun drop(id: String) {
        val p = packs[id] ?: return
        runCatching { p.zip.close() }
        p.file.delete()
        publish(packs - id)
    }

    // --- Selection --------------------------------------------------------------------------

    /** True until the user (or the first-run default) has chosen any states at all. */
    val selectionUnset: Boolean get() = synchronized(this) { store.selected == null }

    @Synchronized
    fun setSelected(slugs: Set<String>) {
        saveStore(store.copy(selected = slugs))
        pruneUnselected()
    }

    @Synchronized
    fun select(slug: String) = setSelected(store.selected.orEmpty() + slug)

    /** Take a state off the phone. Its pack is deleted straight away. */
    @Synchronized
    fun deselect(slug: String) = setSelected(store.selected.orEmpty() - slug)

    /**
     * Delete packs whose states are no longer wanted. The combined 0.15.0 pack goes as soon as
     * any of its states is deselected — it can't be cut in half — and the rest are downloaded
     * on their own.
     */
    @Synchronized
    fun pruneUnselected() {
        val want = store.selected ?: return
        val unwanted = packs.filter { (id, pack) ->
            if (id == LEGACY) !pack.meta.regions.all { it in want } else id !in want
        }.keys
        if (unwanted.isEmpty()) return
        unwanted.forEach(::drop)
        saveStore(store.copy(installed = store.installed.filterKeys { it in want }))
        DiagLog.log("pack", "removed ${unwanted.joinToString()}")
    }

    // --- Installed packs --------------------------------------------------------------------

    /** Any pack is installed. */
    val ready: Boolean get() = packs.isNotEmpty()

    fun installedInfo(slug: String): Installed? = synchronized(this) { store.installed[slug] }

    /** This state's own pack is on disk (the combined 0.15.0 pack doesn't count). */
    fun hasState(slug: String): Boolean = slug in packs

    /** States whose trails are on the phone, from their own pack or the combined 0.15.0 one. */
    fun installedStates(): Map<String, Info> {
        val out = LinkedHashMap<String, Info>()
        for (p in packs.values) for (r in p.meta.regions) out.putIfAbsent(r, p.info())
        packs.values.filter { it.id != LEGACY }.forEach { out[it.id] = it.info() }
        return out
    }

    fun bytes(): Long = packs.values.sumOf { it.bytes }

    /** Where a download is written before [install] swaps it in. */
    fun partFile(slug: String): File = File(dir.apply { mkdirs() }, "state-$slug.zip.part")

    /**
     * Swap a finished download in. It is opened first — it has to be a zip carrying a meta.json
     * of this schema — so a truncated or wrong file never replaces a working pack.
     */
    @Synchronized
    fun install(slug: String, part: File, installed: Installed) {
        ZipFile(part).use { z ->
            val m = json.decodeFromString<Meta>(z.getInputStream(z.getEntry("meta.json")).readBytes().decodeToString())
            check(m.schema in SCHEMAS) { "pack schema ${m.schema}, app reads $SCHEMAS" }
        }
        packs[slug]?.let { runCatching { it.zip.close() } }
        val file = File(dir, "state-$slug.zip")
        if (!part.renameTo(file)) {
            part.copyTo(file, overwrite = true)
            part.delete()
        }
        saveStore(store.copy(installed = store.installed + (slug to installed)))
        val opened = open(slug, file) ?: error("installed pack for $slug didn't open")
        publish(packs + (slug to opened))
        DiagLog.log("pack", "installed $slug")
    }

    /** Delete the combined 0.15.0 pack once every one of its states has its own. */
    @Synchronized
    fun retireLegacyIfReplaced() {
        val legacy = packs[LEGACY] ?: return
        if (legacy.meta.regions.all { it in packs }) {
            drop(LEGACY)
            File(dir, "installed.json").delete() // 0.15.0's record of the combined pack
            DiagLog.log("pack", "retired the combined pack; its states have their own now")
        }
    }

    // --- Index + update checks --------------------------------------------------------------

    val indexUpdatedAt: String get() = synchronized(this) { store.indexUpdatedAt }
    val checkedAt: Long get() = synchronized(this) { store.checkedAt }

    @Synchronized
    fun saveIndex(text: String, updatedAt: String) {
        val parsed = json.decodeFromString<Index>(text)
        dir.mkdirs()
        indexFile.writeText(text)
        _index.value = parsed
        saveStore(store.copy(indexUpdatedAt = updatedAt))
    }

    @Synchronized
    fun recordCheck(now: Long = System.currentTimeMillis()) = saveStore(store.copy(checkedAt = now))

    /** The indexed states that contain [p] — two where outlines overlap along a border. */
    fun statesAt(p: GeoPoint): List<StateEntry> = _index.value?.states.orEmpty().filter { it.contains(p) }

    /**
     * The indexed states within [meters] of [p]: containing it, or containing one of eight points
     * on a circle that size around it. A metro split by a state line needs both states — Kansas
     * City's downtown tile is covered only by Kansas and Missouri together — so choosing just the
     * state underfoot would leave the city centre loading from Overpass.
     */
    fun statesAround(p: GeoPoint, meters: Double): List<StateEntry> {
        val dLat = meters / 111_320.0
        val dLon = dLat / cos(Math.toRadians(p.lat)).coerceAtLeast(0.1)
        val probes = listOf(p) + (0 until 8).map { i ->
            val a = Math.PI / 4 * i
            GeoPoint(p.lat + dLat * kotlin.math.sin(a), p.lon + dLon * kotlin.math.cos(a))
        }
        return _index.value?.states.orEmpty().filter { st -> probes.any(st::contains) }
    }

    fun stateName(slug: String): String =
        _index.value?.states?.firstOrNull { it.slug == slug }?.name
            ?: slug.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    // --- Reading ----------------------------------------------------------------------------

    /** True when the installed packs hold everything around [center]. */
    fun covers(center: GeoPoint): Boolean {
        val snapshot = packs
        if (snapshot.isEmpty()) return false
        val x = floor(center.lon / TILE_DEG).toInt()
        val y = floor(center.lat / TILE_DEG).toInt()
        val key = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        synchronized(coverCache) { coverCache[key] }?.let { return it }
        val covered = snapshot.values.any { "${x}_$y" in it.meta.covered } || outlinesCover(snapshot.values, x, y)
        synchronized(coverCache) { coverCache[key] = covered }
        return covered
    }

    /** Every point of a 5×5 grid over the tile lies inside some installed state. */
    private fun outlinesCover(packs: Collection<Pack>, x: Int, y: Int): Boolean {
        val shaped = packs.filter { it.rings.isNotEmpty() }
        if (shaped.isEmpty()) return false
        for (i in 0..4) for (j in 0..4) {
            val lon = (x + i / 4.0) * TILE_DEG
            val lat = (y + j / 4.0) * TILE_DEG
            if (shaped.none { it.contains(lon, lat) }) return false
        }
        return true
    }

    /**
     * Tiles to read for a circle: the ones it touches plus one ring, since an element is stored
     * in the tile holding its centre and can reach up to a tile beyond it.
     */
    fun tilesFor(center: GeoPoint, radiusMeters: Int): List<Pair<Int, Int>> {
        val dLat = radiusMeters / 111_320.0
        val dLon = dLat / cos(Math.toRadians(center.lat)).coerceAtLeast(0.1)
        val x0 = floor((center.lon - dLon) / TILE_DEG).toInt() - 1
        val x1 = floor((center.lon + dLon) / TILE_DEG).toInt() + 1
        val y0 = floor((center.lat - dLat) / TILE_DEG).toInt() - 1
        val y1 = floor((center.lat + dLat) / TILE_DEG).toInt() + 1
        return (x0..x1).flatMap { x -> (y0..y1).map { y -> x to y } }
    }

    /** The wide-element file of every installed state the circle reaches. */
    fun wideSources(kind: String, center: GeoPoint, radiusMeters: Int): List<TileSource> {
        val dLat = radiusMeters / 111_320.0
        val dLon = dLat / cos(Math.toRadians(center.lat)).coerceAtLeast(0.1)
        val name = "$kind/wide.json"
        return packs.values.mapNotNull { p ->
            val b = p.bbox
            val reaches = center.lon + dLon >= b[0] && center.lat + dLat >= b[1] &&
                center.lon - dLon <= b[2] && center.lat - dLat <= b[3]
            if (!reaches || p.zip.getEntry(name) == null) null
            else TileSource("pack_${p.id}_${p.meta.built}_$name", p.zip, name)
        }
    }

    /** This tile in every installed pack that has it (neighbouring states overlap at borders). */
    fun tileSources(kind: String, x: Int, y: Int): List<TileSource> {
        val name = "$kind/${x}_$y.json"
        return packs.values.mapNotNull { p ->
            if (p.zip.getEntry(name) == null) null else TileSource("pack_${p.id}_${p.meta.built}_$name", p.zip, name)
        }
    }

    companion object {
        /** Newest pack layout; must match PACK_SCHEMA in trailpack/build_pack.py. */
        const val SCHEMA = 2

        /** Layouts this app reads: 1 is the combined pack 0.15.0 downloaded. */
        val SCHEMAS = 1..SCHEMA
        const val TILE_DEG = 0.25
        const val LEGACY = "legacy"
        private const val LEGACY_FILE = "trailpack.zip"
        const val INDEX_ASSET = "trailpack-index.json"
        fun assetFor(slug: String) = "trailpack-$slug.zip"
    }
}
