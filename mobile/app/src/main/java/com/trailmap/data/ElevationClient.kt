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
 * Resamples the polyline (≤200 pts via [Geo.resample]) to bound API load, then fetches
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

    suspend fun profile(points: List<GeoPoint>): ElevationProfile {
        if (points.size < 2) return ElevationProfile.EMPTY

        return try {
            val sampled = Geo.resample(points)
            if (sampled.size < 2) return ElevationProfile.EMPTY

            // Fetch elevations chunked at the public instance's 100-location cap,
            // sequentially, with a ~1.1s gap between requests (never before the first).
            val elevations = ArrayList<Double?>(sampled.size)
            var first = true
            for (chunk in sampled.chunked(CHUNK_SIZE)) {
                if (!first) delay(REQUEST_GAP_MS)
                first = false
                elevations.addAll(fetchChunk(chunk))
            }
            if (elevations.size != sampled.size) return ElevationProfile.EMPTY

            // Cumulative distance along the *resampled* polyline (x), elevation (y).
            // Null elevations carry forward the previous value; a leading null is dropped.
            val pts = ArrayList<ElevPoint>(sampled.size)
            var cumDist = 0.0
            var lastElev: Double? = null
            for (i in sampled.indices) {
                if (i > 0) cumDist += Geo.haversineMeters(sampled[i - 1], sampled[i])
                val raw = elevations[i]
                val elev = raw ?: lastElev ?: continue
                lastElev = elev
                pts.add(ElevPoint(cumDist, elev))
            }
            if (pts.isEmpty()) return ElevationProfile.EMPTY

            var ascent = 0.0
            var descent = 0.0
            var minM = pts.first().elevationMeters
            var maxM = pts.first().elevationMeters
            for (i in pts.indices) {
                val e = pts[i].elevationMeters
                if (e < minM) minM = e
                if (e > maxM) maxM = e
                if (i > 0) {
                    val delta = e - pts[i - 1].elevationMeters
                    if (delta > 0) ascent += delta else descent += -delta
                }
            }

            ElevationProfile(
                points = pts,
                ascentMeters = ascent,
                descentMeters = descent,
                minMeters = minM,
                maxMeters = maxM,
            )
        } catch (_: Exception) {
            ElevationProfile.EMPTY
        }
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
    }
}
