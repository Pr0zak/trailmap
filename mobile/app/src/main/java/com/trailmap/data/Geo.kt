package com.trailmap.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared geodesic helpers used by the Overpass + elevation layers. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance between two points, in meters. */
    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Summed length of a polyline, in meters. */
    fun lengthMeters(points: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += haversineMeters(points[i - 1], points[i])
        return total
    }

    /** Minimum distance from [target] to any vertex of [points], in meters. */
    fun minDistanceToVertices(points: List<GeoPoint>, target: GeoPoint): Double {
        var best = Double.MAX_VALUE
        for (p in points) best = min(best, haversineMeters(p, target))
        return best
    }

    /**
     * Resample a polyline to vertices spaced ~[stepMeters] apart (always keeps the
     * first + last), capped at [maxPoints] total. Used to bound elevation API calls.
     */
    fun resample(points: List<GeoPoint>, stepMeters: Double = 25.0, maxPoints: Int = 200): List<GeoPoint> {
        if (points.size <= 2) return points
        val total = lengthMeters(points)
        if (total == 0.0) return listOf(points.first())
        val step = maxOf(stepMeters, total / (maxPoints - 1))
        val out = ArrayList<GeoPoint>()
        out.add(points.first())
        var nextAt = step
        var acc = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val seg = haversineMeters(a, b)
            if (seg == 0.0) continue
            var segStart = acc
            while (nextAt <= acc + seg) {
                val t = (nextAt - segStart).coerceAtLeast(0.0) / seg
                out.add(GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
                nextAt += step
            }
            acc += seg
            segStart = acc
        }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }
}
