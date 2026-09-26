package com.trailmap.data

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/** What your recorded activities say about one trail. */
data class TrailVisits(
    /** Rides ([RecordedTrack.id]) that went along this trail, newest first. */
    val rides: List<String>,
    /** Walks, hikes and runs that did, newest first. */
    val onFoot: List<String>,
    val lastRidden: Long?,
    val lastOnFoot: Long?,
    /** How much of the trail those rides covered between them, in metres. */
    val riddenMeters: Double,
    val onFootMeters: Double,
    val totalMeters: Double,
    /** The stretches your rides covered, for the map's ridden layer and the route preview. */
    val riddenPaths: List<List<GeoPoint>>,
) {
    val ridden: Boolean get() = rides.isNotEmpty()
    val riddenFraction: Double get() = fraction(riddenMeters)
    val onFootFraction: Double get() = fraction(onFootMeters)

    private fun fraction(m: Double) = if (totalMeters > 0) (m / totalMeters).coerceIn(0.0, 1.0) else 0.0
}

/** A trail a recorded track went along: how much of it, and where along the track it came. */
data class TrailOnTrack(val trail: Trail, val coveredMeters: Double, val order: Int)

/**
 * A spatial index over recorded tracks, for asking which of them ran along a trail.
 *
 * A track counts on a trail when it follows at least [MIN_OVERLAP_M] of it — or half, for a
 * trail shorter than twice that — within [MATCH_M]. Distance along the trail is what's
 * measured, never a single close point: a ride that crosses a trail, or rides the road beside
 * it for a block, doesn't count. Checked against a real ride history before this was
 * written: over nine-tenths of its distance lay on named trails the app draws, which is what
 * makes the result worth showing.
 *
 * The index holds the tracks as segments in a 25 m grid, so each point along a trail looks at
 * nine cells rather than every track. Only the tracks the user actually recorded are held —
 * a few hundred, simplified by myvitals — so it is a few megabytes, built once per sync.
 */
class TrackIndex(tracks: List<RecordedTrack>) {
    private val ids: Array<String>
    private val kinds: Array<ActivityKind>
    private val starts: LongArray

    // Every track's points, flattened; segment s runs from point segFrom[s] to segFrom[s] + 1.
    private val lat: DoubleArray
    private val lon: DoubleArray
    private val segFrom: IntArray
    private val segTrack: IntArray

    // The grid, as a sorted map of cell → segments passing through it (CSR layout).
    private val cellKeys: LongArray
    private val cellFirst: IntArray
    private val cellSegs: IntArray

    /** 1 km cells any track passes through, so trails nowhere near a track cost nothing. */
    private val coarse = HashSet<Long>()

    /** Metres per degree of longitude in the grid's projection (fixed at the tracks' latitude). */
    private val kx: Double

    val size: Int get() = ids.size

    init {
        val usable = tracks.mapNotNull { t ->
            if (!t.kind.ride && !t.kind.onFoot) return@mapNotNull null
            val pts = Polyline.decode(t.polyline)
            if (pts.size < 2) null else t to pts
        }
        ids = Array(usable.size) { usable[it].first.id }
        kinds = Array(usable.size) { usable[it].first.kind }
        starts = LongArray(usable.size) { usable[it].first.start }

        val nPoints = usable.sumOf { it.second.size }
        lat = DoubleArray(nPoints)
        lon = DoubleArray(nPoints)
        val phi0 = if (nPoints == 0) 39.0 else usable.sumOf { (_, p) -> p.sumOf { it.lat } } / nPoints
        kx = M_PER_DEG * cos(Math.toRadians(phi0))

        val from = ArrayList<Int>(nPoints)
        val owner = ArrayList<Int>(nPoints)
        var pairs = LongArray(maxOf(16, nPoints * 3))
        var nPairs = 0
        var p = 0
        for ((ti, entry) in usable.withIndex()) {
            val pts = entry.second
            for ((i, g) in pts.withIndex()) {
                lat[p + i] = g.lat
                lon[p + i] = g.lon
                coarse.add(coarseKey(g.lat, g.lon))
            }
            for (i in 0 until pts.size - 1) {
                val seg = from.size
                if (seg >= MAX_SEGMENTS) break
                from.add(p + i)
                owner.add(ti)
                // Every cell the segment passes through, sampled at half a cell.
                val ax = gx(pts[i].lon); val ay = gy(pts[i].lat)
                val bx = gx(pts[i + 1].lon); val by = gy(pts[i + 1].lat)
                val steps = maxOf(1, ceil(Math.hypot(bx - ax, by - ay) / (CELL_M / 2)).toInt())
                var last = Long.MIN_VALUE
                for (s in 0..steps) {
                    val f = s.toDouble() / steps
                    val key = cellKey(ax + (bx - ax) * f, ay + (by - ay) * f)
                    if (key == last) continue
                    last = key
                    if (nPairs == pairs.size) pairs = pairs.copyOf(pairs.size * 2)
                    pairs[nPairs++] = (key shl SEG_BITS) or seg.toLong()
                    // Long segments (a GPS gap in a simplified track) still mark the coarse grid.
                    if (s % COARSE_EVERY == 0) {
                        coarse.add(coarseKey(pts[i].lat + (pts[i + 1].lat - pts[i].lat) * f, pts[i].lon + (pts[i + 1].lon - pts[i].lon) * f))
                    }
                }
            }
            p += pts.size
        }
        segFrom = from.toIntArray()
        segTrack = owner.toIntArray()

        pairs = pairs.copyOf(nPairs)
        pairs.sort()
        val keys = ArrayList<Long>()
        val first = ArrayList<Int>()
        val segs = IntArray(nPairs)
        var prev = Long.MIN_VALUE
        for (i in 0 until nPairs) {
            val key = pairs[i] ushr SEG_BITS
            if (key != prev) {
                keys.add(key)
                first.add(i)
                prev = key
            }
            segs[i] = (pairs[i] and SEG_MASK).toInt()
        }
        first.add(nPairs)
        cellKeys = keys.toLongArray()
        cellFirst = first.toIntArray()
        cellSegs = segs
    }

    /** Which of the recorded tracks rode or walked [trail], and how much of it. Null if none. */
    fun visits(trail: Trail): TrailVisits? {
        val m = match(trail) ?: return null
        val rides = m.qualified.filter { kinds[it].ride }.sortedByDescending { starts[it] }
        val foot = m.qualified.filter { kinds[it].onFoot }.sortedByDescending { starts[it] }
        if (rides.isEmpty() && foot.isEmpty()) return null
        val rideSet = rides.toHashSet()
        val footSet = foot.toHashSet()

        var riddenMeters = 0.0
        var footMeters = 0.0
        val riddenPaths = ArrayList<List<GeoPoint>>()
        var run: ArrayList<GeoPoint>? = null
        var runPath = -1
        var runSeg = -1
        for (piece in m.pieces) {
            val hits = piece.hits
            val byRide = hits != null && hits.any { it in rideSet }
            val byFoot = hits != null && hits.any { it in footSet }
            if (byRide) riddenMeters += piece.meters
            if (byFoot) footMeters += piece.meters
            // Consecutive ridden pieces of one path become one polyline. Pieces of the same
            // segment are collinear, so the run's last point moves along instead of adding one:
            // the stretch keeps only the trail's own vertices and its two ends, not a point
            // every 10 m. That was ~3,300 points for a 20-mile greenway, drawn with a wide
            // stroke on every scroll frame of the trail page and in the map's ridden layer.
            if (byRide) {
                val open = run
                if (open != null && runPath == piece.path && open.last() == piece.a) {
                    if (runSeg == piece.seg && open.size >= 2) open[open.lastIndex] = piece.b else open.add(piece.b)
                } else {
                    if (open != null && open.size >= 2) riddenPaths.add(open)
                    run = arrayListOf(piece.a, piece.b)
                    runPath = piece.path
                }
                runSeg = piece.seg
            } else {
                run?.let { if (it.size >= 2) riddenPaths.add(it) }
                run = null
            }
        }
        run?.let { if (it.size >= 2) riddenPaths.add(it) }

        return TrailVisits(
            rides = rides.map { ids[it] },
            onFoot = foot.map { ids[it] },
            lastRidden = rides.firstOrNull()?.let { starts[it] },
            lastOnFoot = foot.firstOrNull()?.let { starts[it] },
            riddenMeters = riddenMeters,
            onFootMeters = footMeters,
            totalMeters = m.total,
            riddenPaths = riddenPaths,
        )
    }

    /**
     * The trails in [trails] that the track [trackId] went along, in the order it reached them,
     * with how much of each it covered. Used to turn a recorded ride into a planned one.
     */
    fun trailsAlong(trackId: String, trails: List<Trail>): List<TrailOnTrack> {
        val ti = ids.indexOf(trackId)
        if (ti < 0) return emptyList()
        return trails.mapNotNull { trail ->
            val m = match(trail, only = ti) ?: return@mapNotNull null
            if (ti !in m.qualified) return@mapNotNull null
            TrailOnTrack(trail, m.perTrack[ti] ?: 0.0, m.firstSeg[ti] ?: Int.MAX_VALUE)
        }.sortedBy { it.order }
    }

    /** One sampling step along [path]'s segment [seg], from [a] to [b]. */
    private class Piece(val path: Int, val seg: Int, val a: GeoPoint, val b: GeoPoint, val meters: Double, val hits: IntArray?)

    private class Match(
        val pieces: List<Piece>,
        val total: Double,
        val perTrack: Map<Int, Double>,
        val firstSeg: Map<Int, Int>,
        val qualified: List<Int>,
    )

    /**
     * Per-call working arrays, so the index itself stays read-only and can be asked from more
     * than one coroutine at once (the map's visits and a recorded ride's trail list).
     */
    private class Scratch(n: Int) {
        /** Per track, the query that last hit it: a set cleared by bumping [query]. */
        val seen = IntArray(n)
        var query = 0

        /** Per track, the earliest of its segments that came near this trail. */
        val firstSeg = IntArray(n) { Int.MAX_VALUE }
        val meters = DoubleArray(n)
        val touched = ArrayList<Int>()
        val out = IntArray(n)
    }

    /**
     * Walk [trail] in steps of at most [STEP_M] and ask, at the middle of each step, which
     * tracks pass within [MATCH_M]. [only] restricts the question to one track.
     */
    private fun match(trail: Trail, only: Int = -1): Match? {
        if (ids.isEmpty() || !mayTouch(trail)) return null
        val cosLat = cos(Math.toRadians(trail.center.lat))
        val scratch = Scratch(ids.size)
        val pieces = ArrayList<Piece>()
        var total = 0.0
        for ((pi, path) in trail.paths.withIndex()) {
            for (i in 0 until path.size - 1) {
                val a = path[i]
                val b = path[i + 1]
                val len = Geo.haversineMeters(a, b)
                if (len <= 0.0) continue
                val k = maxOf(1, ceil(len / STEP_M).toInt())
                val w = len / k
                var prev = a
                for (j in 1..k) {
                    val f = j.toDouble() / k
                    val next = if (j == k) b else GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                    val n = near((prev.lat + next.lat) / 2, (prev.lon + next.lon) / 2, cosLat, only, scratch)
                    val hits = if (n == 0) null else scratch.out.copyOf(n)
                    if (hits != null) for (t in hits) {
                        if (scratch.meters[t] == 0.0) scratch.touched.add(t)
                        scratch.meters[t] += w
                    }
                    pieces.add(Piece(pi, i, prev, next, w, hits))
                    total += w
                    prev = next
                }
            }
        }
        if (scratch.touched.isEmpty()) return null
        val need = minOf(MIN_OVERLAP_M, total * MIN_FRACTION).coerceAtLeast(MIN_ABS_M)
        return Match(
            pieces = pieces,
            total = total,
            perTrack = scratch.touched.associateWith { scratch.meters[it] },
            firstSeg = scratch.touched.associateWith { scratch.firstSeg[it] },
            qualified = scratch.touched.filter { scratch.meters[it] >= need },
        )
    }

    /**
     * Tracks with a segment within [MATCH_M] of the point, written into the scratch's `out`;
     * returns how many. Records, per track, the earliest segment that came this close — the
     * position along the track, since each track's segments are numbered in riding order.
     */
    private fun near(pLat: Double, pLon: Double, cosLat: Double, only: Int, sc: Scratch): Int {
        val q = ++sc.query
        var n = 0
        val cx = floor(gx(pLon) / CELL_M).toLong()
        val cy = floor(gy(pLat) / CELL_M).toLong()
        for (dx in -1L..1L) for (dy in -1L..1L) {
            val c = cellKeys.binarySearch(((cx + dx) shl 21) or (cy + dy))
            if (c < 0) continue
            for (i in cellFirst[c] until cellFirst[c + 1]) {
                val s = cellSegs[i]
                val t = segTrack[s]
                if (only >= 0 && t != only) continue
                // Already counted here, and nothing earlier along the track to learn.
                if (sc.seen[t] == q && sc.firstSeg[t] <= s) continue
                if (!within(pLat, pLon, cosLat, segFrom[s])) continue
                if (s < sc.firstSeg[t]) sc.firstSeg[t] = s
                if (sc.seen[t] != q) {
                    sc.seen[t] = q
                    sc.out[n++] = t
                }
            }
        }
        return n
    }

    /** Is the segment starting at point [a] within [MATCH_M] of the point? Local flat metres. */
    private fun within(pLat: Double, pLon: Double, cosLat: Double, a: Int): Boolean {
        val ax = (lon[a] - pLon) * M_PER_DEG * cosLat
        val ay = (lat[a] - pLat) * M_PER_DEG
        val bx = (lon[a + 1] - pLon) * M_PER_DEG * cosLat
        val by = (lat[a + 1] - pLat) * M_PER_DEG
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        return px * px + py * py <= MATCH_M * MATCH_M
    }

    private fun mayTouch(trail: Trail): Boolean {
        for (path in trail.paths) for (g in path) {
            val cx = floor(gx(g.lon) / COARSE_M).toLong()
            val cy = floor(gy(g.lat) / COARSE_M).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) {
                if (((cx + dx) shl 21) or (cy + dy) in coarse) return true
            }
        }
        return false
    }

    private fun gx(lonDeg: Double) = (lonDeg + 180.0) * kx
    private fun gy(latDeg: Double) = (latDeg + 90.0) * M_PER_DEG
    private fun cellKey(x: Double, y: Double): Long = (floor(x / CELL_M).toLong() shl 21) or floor(y / CELL_M).toLong()
    private fun coarseKey(latDeg: Double, lonDeg: Double): Long =
        (floor(gx(lonDeg) / COARSE_M).toLong() shl 21) or floor(gy(latDeg) / COARSE_M).toLong()

    companion object {
        /** How close a track has to run to a trail to be on it. */
        const val MATCH_M = 20.0

        /** How much of a trail a track has to follow to count as having ridden it… */
        const val MIN_OVERLAP_M = 300.0

        /** …or this share of a shorter trail… */
        const val MIN_FRACTION = 0.5

        /** …but never less than this, or every crossing of a short connector would count. */
        const val MIN_ABS_M = 60.0

        /** Trail sampling step. */
        private const val STEP_M = 10.0

        /** Grid cell. Must exceed [MATCH_M] so the 3×3 neighbourhood holds every candidate. */
        private const val CELL_M = 25.0
        private const val COARSE_M = 1000.0

        /** Mark the coarse grid every this many fine samples along a segment (~500 m). */
        private const val COARSE_EVERY = 40

        private const val M_PER_DEG = 111_132.0

        /** Segment ids share a Long with the 42-bit cell key. */
        private const val SEG_BITS = 21
        private const val SEG_MASK = (1L shl SEG_BITS) - 1
        private const val MAX_SEGMENTS = 1 shl SEG_BITS
    }
}
