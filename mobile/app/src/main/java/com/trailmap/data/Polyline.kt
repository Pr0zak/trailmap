package com.trailmap.data

/**
 * Google's encoded polyline format at precision 5 — the format myvitals stores every activity
 * track in, whichever provider it came from (Strava, Garmin FIT imports, Health Connect).
 *
 * Precision 5 is what every myvitals client decodes with; at precision 6 the same bytes land a
 * factor of ten off, somewhere in the Gulf of Guinea.
 */
object Polyline {
    /**
     * Decode [encoded] into points in order. A truncated string yields the points that decoded
     * cleanly before the damage, rather than throwing away the whole track.
     */
    fun decode(encoded: String): List<GeoPoint> {
        val out = ArrayList<GeoPoint>(encoded.length / 4 + 1)
        val n = encoded.length
        var index = 0
        var lat = 0
        var lon = 0
        while (index < n) {
            val dLat = next(encoded, index) ?: return out
            index = dLat.second
            val dLon = next(encoded, index) ?: return out
            index = dLon.second
            lat += dLat.first
            lon += dLon.first
            out.add(GeoPoint(lat / 1e5, lon / 1e5))
        }
        return out
    }

    /** One signed value starting at [start], and the index just past it; null if cut short. */
    private fun next(s: String, start: Int): Pair<Int, Int>? {
        var index = start
        var result = 0
        var shift = 0
        while (true) {
            if (index >= s.length) return null
            val b = s[index++].code - 63
            result = result or ((b and 0x1F) shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val value = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
        return value to index
    }
}
