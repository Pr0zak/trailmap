package com.trailmap.data

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Puts a trail's pieces in riding order.
 *
 * A named trail is assembled from however many OSM ways carry that name, and they arrive in
 * whatever order Overpass returned them, pointing whichever way each was drawn. Flattening them
 * into one polyline — which is what the elevation profile used to do — measures every jump from
 * the end of one piece to the start of an unrelated one. The Gary L. Haller Trail is 81 ways and
 * 16.26 mi; flattened, it measured 135.4 mi, the chart's axis ran past 116 mi, the climb came
 * out at +5,265 ft, and scrubbing the chart sent the marker back and forth across the map.
 *
 * The pieces are treated as a graph instead:
 *  1. Ways are split wherever they share a vertex with another way (a junction), and a way end
 *     that stops just short of another way (within [SNAP_METERS]) is joined to it.
 *  2. Pieces that still don't touch — a trail's named ways are routinely interrupted where it
 *     crosses a road or a differently named bridge — are linked by straight "gap" edges, the
 *     shortest ones that connect everything (a minimum spanning tree).
 *  3. The trunk is the longest shortest path through that graph (found with two Dijkstra
 *     passes), i.e. end to end. Everything is walked depth-first from one end of it, spurs
 *     before the trunk, then the resulting runs are sorted by where they meet the trunk — so
 *     spurs slot in where they branch off instead of piling up at the end.
 *
 * The output is a list of continuous runs. Gap edges and backtracks separate runs and are never
 * counted as distance, so the runs together measure exactly what the trail's ways measure.
 */
object TrailRoute {
    /** A way end this close to another way's vertex is taken to meet it. */
    const val SNAP_METERS = 30.0

    fun order(paths: List<List<GeoPoint>>): List<List<GeoPoint>> {
        val input = paths.filter { it.size >= 2 }
        if (input.size <= 1) return input
        return Router(input).route()
    }

    /** Total length of the runs — the distance a profile along them covers. */
    fun lengthMeters(runs: List<List<GeoPoint>>): Double = runs.sumOf { Geo.lengthMeters(it) }

    private class Edge(
        val u: Long,
        val v: Long,
        /** Geometry from u to v, or null for a gap edge (a straight hop between pieces). */
        val pts: List<GeoPoint>?,
        val length: Double,
    ) {
        fun other(n: Long) = if (n == u) v else u
    }

    private class Router(private val paths: List<List<GeoPoint>>) {
        private val edges = ArrayList<Edge>()
        private val adj = HashMap<Long, MutableList<Int>>()
        private val coord = HashMap<Long, GeoPoint>()

        /** Vertices are identified by their coordinates at ~1 cm, which is how OSM ways share nodes. */
        private fun key(p: GeoPoint): Long =
            (p.lat * 1e7).roundToLong() * 4_000_000_000L + (p.lon * 1e7).roundToLong()

        private fun addEdge(u: Long, v: Long, pts: List<GeoPoint>?, length: Double) {
            val i = edges.size
            edges.add(Edge(u, v, pts, length))
            adj.getOrPut(u) { ArrayList(2) }.add(i)
            adj.getOrPut(v) { ArrayList(2) }.add(i)
        }

        fun route(): List<List<GeoPoint>> {
            buildGraph()
            joinComponents()
            val nodes = adj.keys
            val a0 = farthest(nodes.first()).first
            val a1 = farthest(a0).first
            // Read like a map: south → north, or west → east when the trail runs that way.
            val pa = coord.getValue(a0)
            val pb = coord.getValue(a1)
            val eastWest = abs(pa.lon - pb.lon) * cos(Math.toRadians(pa.lat)) > abs(pa.lat - pb.lat)
            val startAtA = if (eastWest) pa.lon <= pb.lon else pa.lat <= pb.lat
            val start = if (startAtA) a0 else a1
            val end = if (startAtA) a1 else a0
            val (_, prev) = dijkstra(start)
            val trunk = HashSet<Int>()
            val trunkNodes = ArrayList<Long>()
            var n = end
            trunkNodes.add(n)
            while (n != start) {
                val (p, e) = prev.getValue(n)
                trunk.add(e)
                n = p
                trunkNodes.add(n)
            }
            trunkNodes.reverse()
            val runs = walk(start, trunk)
            return sortAlongTrunk(runs, trunkNodes, prev)
        }

        private fun buildGraph() {
            val count = HashMap<Long, Int>()
            for (p in paths) for (k in p.mapTo(HashSet()) { key(it) }) count[k] = (count[k] ?: 0) + 1
            val split = HashSet<Long>()
            count.forEach { (k, c) -> if (c > 1) split.add(k) }

            // Way ends that stop just short of another way: snap onto its nearest vertex.
            // Merged as a union, not a lookup: two way ends that stop short of each other each
            // snap to the other, and a plain alias map just swapped them.
            val grid = VertexGrid(paths)
            val parent = HashMap<Long, Long>()
            fun find(k: Long): Long {
                var r = k
                while (true) r = parent[r]?.takeIf { it != r } ?: break
                return r
            }
            paths.forEachIndexed { i, p ->
                for (end in listOf(p.first(), p.last())) {
                    val k = key(end)
                    if ((count[k] ?: 0) > 1) continue
                    val near = grid.nearest(end, excludePath = i, within = SNAP_METERS) ?: continue
                    val nk = key(near)
                    val a = find(k)
                    val b = find(nk)
                    if (a != b) parent[a] = b
                    split.add(nk)
                }
            }
            fun node(p: GeoPoint): Long {
                val n = find(key(p))
                coord.putIfAbsent(n, p)
                return n
            }
            for (p in paths) {
                var run = arrayListOf(p[0])
                for (i in 1 until p.size) {
                    run.add(p[i])
                    if (i == p.lastIndex || key(p[i]) in split) {
                        addEdge(node(run.first()), node(run.last()), run, Geo.lengthMeters(run))
                        run = arrayListOf(p[i])
                    }
                }
            }
        }

        /** Link disconnected pieces with the shortest straight gaps that connect them all. */
        private fun joinComponents() {
            val comp = HashMap<Long, Int>()
            val members = ArrayList<List<Long>>()
            for (start in adj.keys) {
                if (start in comp) continue
                val id = members.size
                val list = arrayListOf(start)
                comp[start] = id
                var i = 0
                while (i < list.size) {
                    val x = list[i++]
                    for (e in adj.getValue(x)) {
                        val y = edges[e].other(x)
                        if (y !in comp) { comp[y] = id; list.add(y) }
                    }
                }
                members.add(list)
            }
            if (members.size <= 1) return
            class Gap(val d: Double, val a: Int, val b: Int, val x: Long, val y: Long)
            val gaps = ArrayList<Gap>()
            for (a in members.indices) for (b in a + 1 until members.size) {
                var best: Gap? = null
                for (x in members[a]) for (y in members[b]) {
                    val d = Geo.haversineMeters(coord.getValue(x), coord.getValue(y))
                    if (best == null || d < best.d) best = Gap(d, a, b, x, y)
                }
                best?.let { gaps.add(it) }
            }
            gaps.sortBy { it.d }
            val parent = IntArray(members.size) { it }
            fun find(i: Int): Int {
                var r = i
                while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
                return r
            }
            for (g in gaps) {
                val ra = find(g.a)
                val rb = find(g.b)
                if (ra != rb) {
                    parent[ra] = rb
                    addEdge(g.x, g.y, null, g.d)
                }
            }
        }

        private fun dijkstra(src: Long): Pair<Map<Long, Double>, Map<Long, Pair<Long, Int>>> {
            val dist = HashMap<Long, Double>()
            val prev = HashMap<Long, Pair<Long, Int>>()
            val pq = PriorityQueue<Pair<Double, Long>>(compareBy { it.first })
            dist[src] = 0.0
            pq.add(0.0 to src)
            while (pq.isNotEmpty()) {
                val (d, x) = pq.poll()!!
                if (d > dist.getValue(x)) continue
                for (e in adj.getValue(x)) {
                    val y = edges[e].other(x)
                    val nd = d + edges[e].length
                    if (nd < (dist[y] ?: Double.MAX_VALUE)) {
                        dist[y] = nd
                        prev[y] = x to e
                        pq.add(nd to y)
                    }
                }
            }
            return dist to prev
        }

        private fun farthest(src: Long): Pair<Long, Double> {
            val (dist, _) = dijkstra(src)
            val best = dist.maxByOrNull { it.value }!!
            return best.key to best.value
        }

        /**
         * Depth-first over the edges from [start], short side branches before the trunk. A new
         * run begins after every gap edge and every backtrack, since neither is ground you ride.
         */
        private fun walk(start: Long, trunk: Set<Int>): MutableList<List<GeoPoint>> {
            val used = BooleanArray(edges.size)
            val runs = ArrayList<List<GeoPoint>>()
            var cur = ArrayList<GeoPoint>()
            var pos = start
            val stack = ArrayDeque<Long>().apply { addLast(start) }
            while (stack.isNotEmpty()) {
                val x = stack.last()
                val next = adj.getValue(x)
                    .filter { !used[it] }
                    .minWithOrNull(compareBy<Int>({ it in trunk }, { edges[it].length }))
                if (next == null) {
                    stack.removeLast()
                    continue
                }
                used[next] = true
                val e = edges[next]
                val y = e.other(x)
                if (e.pts == null || pos != x) {
                    if (cur.size >= 2) runs.add(cur)
                    cur = ArrayList()
                }
                if (e.pts != null) {
                    val pts = if (e.u == x) e.pts else e.pts.asReversed()
                    // Drop the shared vertex, but keep a snapped end's own point: the few metres
                    // between two ways that nearly meet are still trail.
                    if (cur.isEmpty() || cur.last() != pts.first()) cur.addAll(pts) else cur.addAll(pts.subList(1, pts.size))
                }
                pos = y
                stack.addLast(y)
            }
            if (cur.size >= 2) runs.add(cur)
            return runs
        }

        /** Order runs by where they meet the trunk, so a spur sits where it branches off. */
        private fun sortAlongTrunk(
            runs: MutableList<List<GeoPoint>>,
            trunkNodes: List<Long>,
            prev: Map<Long, Pair<Long, Int>>,
        ): List<List<GeoPoint>> {
            val line = ArrayList<GeoPoint>()
            val along = ArrayList<Double>()
            var acc = 0.0
            fun push(p: GeoPoint) {
                if (line.isNotEmpty()) acc += Geo.haversineMeters(line.last(), p)
                line.add(p)
                along.add(acc)
            }
            push(coord.getValue(trunkNodes.first()))
            for (i in 1 until trunkNodes.size) {
                val to = trunkNodes[i]
                val e = edges[prev.getValue(to).second]
                val from = trunkNodes[i - 1]
                val pts = e.pts?.let { if (e.u == from) it else it.asReversed() }
                    ?: listOf(coord.getValue(from), coord.getValue(to))
                for (j in 1 until pts.size) push(pts[j])
            }
            val grid = VertexGrid(listOf(line))
            fun position(p: GeoPoint): Double {
                val i = grid.nearestIndex(p) ?: line.indices.minBy { Geo.haversineMeters(line[it], p) }
                return along[i]
            }
            return runs.sortedBy { position(it.first()) }
        }
    }

    /**
     * Vertices bucketed on a ~[SNAP_METERS] grid, so "nearest vertex of another way" doesn't have
     * to compare against every vertex of a trail that can run to tens of thousands of them.
     */
    private class VertexGrid(private val paths: List<List<GeoPoint>>) {
        private val latStep = SNAP_METERS / 111_320.0
        private val lonStep: Double = run {
            val lat = paths.firstOrNull()?.firstOrNull()?.lat ?: 0.0
            latStep / cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        }
        private val cells = HashMap<Long, MutableList<Long>>() // cell -> (path shl 32 | index)

        init {
            paths.forEachIndexed { pi, p ->
                p.forEachIndexed { vi, v ->
                    cells.getOrPut(cell(cellX(v), cellY(v))) { ArrayList() }.add((pi.toLong() shl 32) or vi.toLong())
                }
            }
        }

        private fun cellX(p: GeoPoint) = floor(p.lon / lonStep).toLong()
        private fun cellY(p: GeoPoint) = floor(p.lat / latStep).toLong()
        private fun cell(x: Long, y: Long) = x * 1_000_003L + y

        fun nearest(p: GeoPoint, excludePath: Int, within: Double): GeoPoint? {
            var best: GeoPoint? = null
            var bestD = within
            forNeighbours(p, 1) { pi, vi ->
                if (pi == excludePath) return@forNeighbours
                val v = paths[pi][vi]
                val d = Geo.haversineMeters(p, v)
                if (d <= bestD) { bestD = d; best = v }
            }
            return best
        }

        /** Index of the nearest vertex of path 0, searching outward ring by ring. */
        fun nearestIndex(p: GeoPoint): Int? {
            for (r in 1..64) {
                var best = -1
                var bestD = Double.MAX_VALUE
                forNeighbours(p, r) { _, vi ->
                    val d = Geo.haversineMeters(p, paths[0][vi])
                    if (d < bestD) { bestD = d; best = vi }
                }
                if (best >= 0) return best
            }
            return null
        }

        private inline fun forNeighbours(p: GeoPoint, r: Int, f: (Int, Int) -> Unit) {
            val cx = cellX(p)
            val cy = cellY(p)
            for (dx in -r..r) for (dy in -r..r) {
                cells[cell(cx + dx, cy + dy)]?.forEach { f((it shr 32).toInt(), (it and 0xffffffffL).toInt()) }
            }
        }
    }
}
