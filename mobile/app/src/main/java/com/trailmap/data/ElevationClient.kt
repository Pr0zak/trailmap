package com.trailmap.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Elevation-profile layer backed by Open-Topo-Data (dataset `ned10m`, 10m US DEM).
 *
 * Samples the trail (~200 pts, in riding order via [TrailRoute]) to bound API load, then fetches
 * elevations in chunks of 100 (the public instance's per-request cap), one request/sec.
 * Any failure collapses to [ElevationProfile.EMPTY] — the ViewModel reads EMPTY as
 * "no profile", so this layer never throws.
 */
class ElevationClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class OtdResponse(
        val results: List<OtdResult> = emptyList(),
        val status: String = "",
    )

    @Serializable
    private data class OtdResult(
        val elevation: Double? = null,
    )

    /**
     * Profile along a trail's pieces, taken in riding order ([TrailRoute.order]). The distance
     * axis only advances along the pieces themselves — the hop from the end of one to the start
     * of the next is a road crossing, a gap in the map data or a backtrack off a spur, not trail.
     */
    suspend fun profile(paths: List<List<GeoPoint>>): ElevationProfile {
        return try {
            // Off the main thread: ordering a long trail's pieces is graph work.
            val samples = withContext(Dispatchers.Default) { sample(TrailRoute.order(paths)) }
            if (samples.size < 2) return ElevationProfile.EMPTY

            // Fetch elevations chunked at the public instance's 100-location cap,
            // sequentially, with a ~1.1s gap between requests (never before the first).
            val elevations = ArrayList<Double?>(samples.size)
            var first = true
            for (chunk in samples.chunked(CHUNK_SIZE)) {
                if (!first) delay(REQUEST_GAP_MS)
                first = false
                elevations.addAll(fetchChunk(chunk.map { it.point }))
            }
            if (elevations.size != samples.size) return ElevationProfile.EMPTY

            // Null elevations carry forward the previous value; a leading null is dropped.
            val pts = ArrayList<ElevPoint>(samples.size)
            var lastElev: Double? = null
            var pendingGap = false
            for (i in samples.indices) {
                pendingGap = pendingGap || samples[i].gapBefore
                val elev = elevations[i] ?: lastElev ?: continue
                lastElev = elev
                pts.add(ElevPoint(samples[i].distance, elev, samples[i].point, gapBefore = pendingGap && pts.isNotEmpty()))
                pendingGap = false
            }
            if (pts.isEmpty()) return ElevationProfile.EMPTY
            val (ascent, descent) = climb(pts)
            ElevationProfile(
                points = pts,
                ascentMeters = ascent,
                descentMeters = descent,
                minMeters = pts.minOf { it.elevationMeters },
                maxMeters = pts.maxOf { it.elevationMeters },
            )
        } catch (_: Exception) {
            ElevationProfile.EMPTY
        }
    }

    private class Sample(val distance: Double, val point: GeoPoint, val gapBefore: Boolean)

    /**
     * Points every ~[STEP_METERS] along the runs (spaced out further on long trails, to stay near
     * [MAX_SAMPLES]), each with its distance along the trail. Every run contributes its first
     * point, flagged as following a gap unless it is the very first.
     */
    private fun sample(runs: List<List<GeoPoint>>): List<Sample> {
        val total = TrailRoute.lengthMeters(runs)
        if (total <= 0.0) return emptyList()
        val step = maxOf(STEP_METERS, total / (MAX_SAMPLES - 1))
        val out = ArrayList<Sample>()
        var acc = 0.0
        for ((ri, run) in runs.withIndex()) {
            out.add(Sample(acc, run.first(), gapBefore = ri > 0))
            var nextAt = acc + step
            for (i in 1 until run.size) {
                val a = run[i - 1]
                val b = run[i]
                val seg = Geo.haversineMeters(a, b)
                if (seg == 0.0) continue
                while (nextAt <= acc + seg) {
                    val t = (nextAt - acc) / seg
                    out.add(Sample(nextAt, GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t), false))
                    nextAt += step
                }
                acc += seg
            }
            // Close the run at its true end, unless a sample already sits almost on it.
            if (acc - out.last().distance > step * 0.25) out.add(Sample(acc, run.last(), false))
        }
        return out
    }

    /**
     * Total climb and descent, in metres.
     *
     * The 10 m elevation model reads the ground, not the deck of a bridge, so a greenway that
     * crosses its creek every mile shows a sharp dip at every crossing — and each one counts as
     * a descent and a climb that nobody rode. A 3-point median removes those single-sample
     * spikes, and a [CLIMB_HYSTERESIS_M] dead band keeps model noise on the flat from adding up.
     * Measured on the Gary L. Haller Trail: +544 ft raw, +237 ft median-filtered.
     * Elevation changes across a gap between runs are not counted.
     */
    private fun climb(pts: List<ElevPoint>): Pair<Double, Double> {
        val smooth = pts.indices.map { i ->
            val lo = if (i > 0 && !pts[i].gapBefore) i - 1 else i
            val hi = if (i < pts.lastIndex && !pts[i + 1].gapBefore) i + 1 else i
            (lo..hi).map { pts[it].elevationMeters }.sorted().let { it[it.size / 2] }
        }
        var ascent = 0.0
        var descent = 0.0
        var ref = smooth[0]
        for (i in 1 until smooth.size) {
            if (pts[i].gapBefore) { ref = smooth[i]; continue }
            val d = smooth[i] - ref
            if (d > CLIMB_HYSTERESIS_M) { ascent += d; ref = smooth[i] }
            else if (d < -CLIMB_HYSTERESIS_M) { descent -= d; ref = smooth[i] }
        }
        return ascent to descent
    }

    /** One Open-Topo-Data GET; returns one elevation (nullable) per input point, in order. */
    private suspend fun fetchChunk(chunk: List<GeoPoint>): List<Double?> = withContext(Dispatchers.IO) {
        val locations = chunk.joinToString("|") { "${it.lat},${it.lon}" }
        val url = "$BASE_URL?locations=$locations"
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val raw = resp.body?.string() ?: throw IOException("empty response")
            val parsed = json.decodeFromString(OtdResponse.serializer(), raw)
            if (parsed.results.size != chunk.size) throw IOException("result count mismatch")
            parsed.results.map { it.elevation }
        }
    }

    private companion object {
        const val BASE_URL = "https://api.opentopodata.org/v1/ned10m"
        const val CHUNK_SIZE = 100
        const val REQUEST_GAP_MS = 1100L
        const val STEP_METERS = 25.0
        const val MAX_SAMPLES = 200
        const val CLIMB_HYSTERESIS_M = 1.0
    }
}
