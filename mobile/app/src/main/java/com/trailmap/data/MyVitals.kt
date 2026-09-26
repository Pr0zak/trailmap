package com.trailmap.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/*
 * myvitals (github.com/Pr0zak/myvitals) is a self-hosted health tracker that already holds the
 * user's recorded rides and walks with their GPS tracks, pulled in from Strava, Garmin, Fitbit
 * and Health Connect, plus the open/closed status of local mountain-bike trails, which it
 * polls from RainoutLine. trailmap reads both from the user's own server. Nothing is written
 * back, and nothing is sent anywhere else.
 */

/** What kind of outing a recorded activity was, from myvitals' free-form type string. */
enum class ActivityKind(val label: String, val ride: Boolean, val onFoot: Boolean) {
    BIKE("Ride", ride = true, onFoot = false),
    EBIKE("E-bike ride", ride = true, onFoot = false),
    MTB("Mountain bike ride", ride = true, onFoot = false),
    WALK("Walk", ride = false, onFoot = true),
    HIKE("Hike", ride = false, onFoot = true),
    RUN("Run", ride = false, onFoot = true),
    OTHER("Activity", ride = false, onFoot = false);

    companion object {
        /**
         * myvitals keeps each provider's own type string — Garmin's `cycling` and `walking`,
         * Strava's `ebikeride` and `ride`, Fitbit's `mountain_biking` and `outdoor_bike`, Health
         * Connect's `cycling` — so this goes by keyword. Order matters: `ebikeride` also
         * contains "ride", and `mountainbikeride` contains "bike".
         */
        fun of(type: String): ActivityKind {
            val t = type.lowercase()
            return when {
                "ebike" in t || "e_bike" in t || "e-bike" in t -> EBIKE
                "mountain" in t || "mtb" in t -> MTB
                // Indoor rides have no track to match, and their speeds would skew the pace.
                "virtual" in t || "indoor" in t || "stationary" in t || "spin" in t -> OTHER
                "cycl" in t || "bik" in t || "ride" in t -> BIKE
                "hik" in t -> HIKE
                "walk" in t -> WALK
                "run" in t || "jog" in t -> RUN
                else -> OTHER
            }
        }
    }
}

/** One recorded activity with a GPS track, as myvitals' activity map serves it. */
@Serializable
data class RecordedTrack(
    /** `source:source_id`, unique within myvitals. */
    val id: String,
    /** The provider's type string; [kind] is what trailmap makes of it. */
    val type: String,
    val name: String? = null,
    /** Start time, epoch milliseconds. */
    val start: Long,
    val durationS: Int = 0,
    val distanceM: Double? = null,
    /**
     * Encoded polyline, precision 5. myvitals simplifies these for its overview map (RDP at
     * 1e-4°, at most 400 points). Checked against the full-fidelity tracks, they match
     * within 1% of the same ridden trails for a tenth of the download.
     */
    val polyline: String,
) {
    val kind: ActivityKind get() = ActivityKind.of(type)
    val distanceMiles: Double? get() = distanceM?.let { it / METERS_PER_MILE }

    /** Average over the whole recording — distance over duration, stops included. */
    val mph: Double?
        get() = if (distanceM != null && distanceM > 0 && durationS > 0) {
            distanceM / METERS_PER_MILE / (durationS / 3600.0)
        } else {
            null
        }
}

/** A trail system's open/closed state, as RainoutLine reports it. */
enum class TrailCondition(val label: String) {
    OPEN("Open"), DELAYED("Delayed"), CLOSED("Closed"), UNKNOWN("Unknown");

    companion object {
        /** RainoutLine's "Delayed" means the day's trail check hasn't been published yet. */
        fun of(status: String?): TrailCondition = when (status?.lowercase()) {
            "open" -> OPEN
            "delayed", "pending" -> DELAYED
            "closed" -> CLOSED
            else -> UNKNOWN
        }
    }
}

/** One trail system on the status board myvitals polls. */
@Serializable
data class TrailStatus(
    val id: Long,
    val name: String,
    /** The trailhead, when myvitals has one pinned. */
    val lat: Double? = null,
    val lon: Double? = null,
    val status: String? = null,
    val comment: String? = null,
    /** When the board's message was posted (RainoutLine's own timestamp when it gives one). */
    val updatedAt: Long? = null,
    /** When myvitals last read the board for this trail. */
    val checkedAt: Long? = null,
    /** RainoutLine's page for this trail, as the server sent it. Open [link], never this. */
    val url: String? = null,
) {
    val condition: TrailCondition get() = TrailCondition.of(status)
    val point: GeoPoint? get() = if (lat != null && lon != null) GeoPoint(lat, lon) else null

    /**
     * [url] when it is a web page. It comes from the server and is handed to whatever app
     * opens links, so anything but http or https (an intent:, a file:, a javascript:) is
     * dropped, and the card shows no link at all.
     */
    val link: String? get() = url?.takeIf { isWebLink(it) }
}

/** An http or https address that parses, with nothing hidden in it: what trailmap will open in a browser. */
internal fun isWebLink(u: String): Boolean =
    (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)) &&
        u.none { it.isWhitespace() || it.isISOControl() } &&
        u.toHttpUrlOrNull() != null

/** The server trailmap reads from, and when it last did. */
@Serializable
data class MyVitalsSettings(
    /** What the user typed, normalised. */
    val url: String = "",
    val token: String = "",
    /** The address that answered as the API: [url] itself, or `[url]/api` for the dashboard's. */
    val apiBase: String = "",
    /** Sync when the app opens (at most hourly) and check trail conditions while it's open. */
    val autoSync: Boolean = true,
    val lastSync: Long = 0L,
    val lastConditionsSync: Long = 0L,
) {
    val configured: Boolean get() = apiBase.isNotBlank() && token.isNotBlank()

    // Whatever prints these settings never shows the key.
    override fun toString() =
        "MyVitalsSettings(url=$url, token=<redacted>, apiBase=$apiBase, autoSync=$autoSync, " +
            "lastSync=$lastSync, lastConditionsSync=$lastConditionsSync)"
}

/**
 * Everything trailmap keeps from myvitals, in one private directory that the backup rules
 * leave out of Android's cloud backup: the server address and token, the tracks, and the
 * trail conditions. Disconnecting deletes the directory.
 */
class MyVitalsStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized fun settings(): MyVitalsSettings = read(SETTINGS) ?: MyVitalsSettings()
    @Synchronized fun saveSettings(s: MyVitalsSettings) = write(SETTINGS, json.encodeToString(s))
    @Synchronized fun tracks(): List<RecordedTrack> = read(TRACKS) ?: emptyList()
    @Synchronized fun saveTracks(t: List<RecordedTrack>) = write(TRACKS, json.encodeToString(t))
    @Synchronized fun conditions(): List<TrailStatus> = read(CONDITIONS) ?: emptyList()
    @Synchronized fun saveConditions(s: List<TrailStatus>) = write(CONDITIONS, json.encodeToString(s))

    /** Forget the server: address, token, tracks and conditions. */
    @Synchronized fun clear() {
        dir.deleteRecursively()
    }

    private inline fun <reified T> read(name: String): T? = runCatching {
        val f = File(dir, name)
        if (f.exists()) json.decodeFromString<T>(f.readText()) else null
    }.getOrNull()

    /** Write through a temp file, so a crash mid-write can't leave half a token or track list. */
    private fun write(name: String, text: String) {
        dir.mkdirs()
        val f = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private companion object {
        const val SETTINGS = "settings.json"
        const val TRACKS = "tracks.json"
        const val CONDITIONS = "conditions.json"
    }
}

/**
 * What the access-key field's Paste button makes of the clipboard: the key with its ends
 * trimmed, or a short reason to show under the field instead.
 */
sealed interface PastedKey {
    class Key(val key: String) : PastedKey {
        override fun toString() = "Key(<redacted>)"
    }

    data class Rejected(val message: String) : PastedKey

    companion object {
        fun of(text: CharSequence?): PastedKey {
            val t = text?.toString()?.trim().orEmpty()
            return when {
                t.isEmpty() -> Rejected("Nothing to paste.")
                // A copied key carries a stray newline at the end, which the trim took off;
                // one in the middle means more than the key was copied.
                t.any { it.isWhitespace() } -> Rejected("That has a space or line break inside it. Copy just the access key.")
                t.length > MAX_KEY -> Rejected("That's too long to be an access key.")
                !MyVitalsClient.isKeyText(t) -> Rejected("That has characters an access key can't contain.")
                else -> Key(t)
            }
        }

        private const val MAX_KEY = 4096
    }
}

/** A myvitals request that failed, and which of the few things it usually is. */
class MyVitalsException(message: String, val reason: Reason) : IOException(message) {
    enum class Reason { UNREACHABLE, AUTH, NOT_MYVITALS, SERVER }
}

/** Reads recorded tracks and trail conditions from a myvitals server. */
class MyVitalsClient(private val http: OkHttpClient = defaultHttp()) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Check [url] and [token] against the server and fetch the tracks in the same trip.
     * Returns the API base that answered and the tracks.
     *
     * The backend answers at its own port, and the dashboard proxies it under `/api`. People
     * will paste either address, so both are tried; a wrong token stops the search at once,
     * since trying the other base can't fix that.
     */
    suspend fun connect(url: String, token: String): Pair<String, List<RecordedTrack>> {
        val base = normalizeUrl(url)
        if (base.isEmpty()) throw MyVitalsException("Enter your myvitals server's address.", MyVitalsException.Reason.NOT_MYVITALS)
        val candidates = if (base.endsWith("/api")) listOf(base) else listOf(base, "$base/api")
        var last: MyVitalsException? = null
        for (candidate in candidates) {
            try {
                return candidate to tracks(candidate, token)
            } catch (e: MyVitalsException) {
                if (e.reason != MyVitalsException.Reason.NOT_MYVITALS) throw e
                last = e
            }
        }
        throw last ?: MyVitalsException(NOT_MYVITALS_TEXT, MyVitalsException.Reason.NOT_MYVITALS)
    }

    /** Every activity myvitals has a GPS track for, newest first. */
    suspend fun tracks(apiBase: String, token: String): List<RecordedTrack> {
        // The all-activities map endpoint: tracks only, pre-simplified and cached server side.
        // A few hundred tracks come to a few hundred KB this way; the full-fidelity list is
        // ten times that.
        val body = get("$apiBase/activities/map?limit=5000", token)
        val resp = runCatching { json.decodeFromString<MapResponse>(body) }
            .getOrElse { throw MyVitalsException(NOT_MYVITALS_TEXT, MyVitalsException.Reason.NOT_MYVITALS) }
        return resp.tracks.mapNotNull { t ->
            if (t.polyline.isBlank()) return@mapNotNull null
            val start = parseInstant(t.startAt) ?: return@mapNotNull null
            RecordedTrack(
                id = "${t.source}:${t.sourceId}",
                type = t.type,
                // Some imports name an activity by its timestamp ("20260921-221311"); the kind
                // and date say that better.
                name = t.name?.trim()?.takeIf { it.isNotEmpty() && !MACHINE_NAME.matches(it) },
                start = start,
                durationS = t.durationS,
                distanceM = t.distanceM,
                polyline = t.polyline,
            )
        }
    }

    /** The trail status board myvitals polls from RainoutLine. Empty when none is set up. */
    suspend fun conditions(apiBase: String, token: String): List<TrailStatus> {
        val body = get("$apiBase/trails", token)
        val resp = runCatching { json.decodeFromString<TrailsResponse>(body) }
            .getOrElse { throw MyVitalsException(NOT_MYVITALS_TEXT, MyVitalsException.Reason.NOT_MYVITALS) }
        return resp.trails.map { t ->
            TrailStatus(
                id = t.id,
                name = t.name,
                lat = t.latitude,
                lon = t.longitude,
                status = t.status,
                comment = t.comment?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = parseInstant(t.sourceTs) ?: parseInstant(t.fetchedAt),
                checkedAt = parseInstant(t.lastSeenAt),
                url = t.url,
            )
        }
    }

    private suspend fun get(url: String, token: String): String = withContext(Dispatchers.IO) {
        // Two steps, so each failure names the right field: OkHttp refuses a bad address and a
        // header value with a character it can't send (anything past ASCII) with the same
        // IllegalArgumentException.
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw MyVitalsException("That isn't a web address.", MyVitalsException.Reason.NOT_MYVITALS)
        }
        val request = try {
            builder
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
        } catch (e: IllegalArgumentException) {
            // AUTH, like a refused key: trying the address with /api can't fix it either.
            throw MyVitalsException(BAD_KEY_TEXT, MyVitalsException.Reason.AUTH)
        }
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw MyVitalsException(
                "Couldn't reach ${request.url.host}. Is the phone on the same network as your " +
                    "myvitals server, or on Tailscale?",
                MyVitalsException.Reason.UNREACHABLE,
            )
        }
        response.use { r ->
            val body = r.body?.string().orEmpty()
            val start = body.trimStart()
            when {
                r.code == 401 || r.code == 403 ->
                    throw MyVitalsException("myvitals didn't accept that access key.", MyVitalsException.Reason.AUTH)
                r.code == 404 || r.code == 405 ->
                    throw MyVitalsException(NOT_MYVITALS_TEXT, MyVitalsException.Reason.NOT_MYVITALS)
                !r.isSuccessful ->
                    throw MyVitalsException("myvitals answered HTTP ${r.code}.", MyVitalsException.Reason.SERVER)
                // The dashboard's own address answers every path with its web page.
                !start.startsWith("{") && !start.startsWith("[") ->
                    throw MyVitalsException(NOT_MYVITALS_TEXT, MyVitalsException.Reason.NOT_MYVITALS)
                else -> body
            }
        }
    }

    @Serializable
    private class MapResponse(val tracks: List<MapTrack>)

    @Serializable
    private class MapTrack(
        val source: String,
        @SerialName("source_id") val sourceId: String,
        val type: String,
        val name: String? = null,
        @SerialName("start_at") val startAt: String,
        @SerialName("duration_s") val durationS: Int = 0,
        @SerialName("distance_m") val distanceM: Double? = null,
        val polyline: String = "",
    )

    @Serializable
    private class TrailsResponse(val trails: List<TrailRow>)

    @Serializable
    private class TrailRow(
        val id: Long,
        val name: String,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val status: String? = null,
        val comment: String? = null,
        @SerialName("source_ts") val sourceTs: String? = null,
        @SerialName("fetched_at") val fetchedAt: String? = null,
        @SerialName("last_seen_at") val lastSeenAt: String? = null,
        @SerialName("rainout_url") val url: String? = null,
    )

    companion object {
        private const val NOT_MYVITALS_TEXT = "That address doesn't answer like a myvitals server."
        const val BAD_KEY_TEXT = "That access key has characters an access key can't contain."

        /** An access key is printable ASCII with no spaces, '!' through '~'. */
        fun isKeyText(key: String): Boolean = key.isNotEmpty() && key.all { it in '!'..'~' }

        /** Names that are only a date/time stamp. */
        private val MACHINE_NAME = Regex("^[0-9 _:.T-]+$")

        /** Trim, drop a trailing slash, and assume http:// when no scheme is given. */
        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty()) return u
            if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
                u = "http://$u"
            }
            return u
        }

        /**
         * A home server is either there within a few seconds or not reachable from where the
         * phone is at all — off the home network, the address simply doesn't route. Failing
         * fast keeps a sync attempt from hanging while the user is out riding.
         */
        private fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Parse myvitals' ISO-8601 timestamps; a value without an offset is taken as UTC. */
internal fun parseInstant(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

/** Run the call on OkHttp's pool and suspend until it answers; cancelling cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}

private const val METERS_PER_MILE = 1609.344
