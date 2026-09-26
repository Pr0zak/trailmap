package com.trailmap.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trailmap.TrailmapApp
import com.trailmap.data.DiagLog
import com.trailmap.data.TrailPacks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps the chosen states' trail packs ([TrailPacks]) on the phone and current.
 *
 * The packs, and `trailpack-index.json` listing them, are assets of the `trailpack` GitHub
 * release, rebuilt weekly by `.github/workflows/trailpack.yml`. That release is a prerelease, so
 * the app updater — which reads `/releases/latest` — never mistakes it for an app version.
 *
 * One run refreshes the index, downloads every chosen state whose pack is missing or older than
 * the release's, and deletes packs of states no longer chosen. It re-reads the choice after each
 * pass, so a state added while a download is running is picked up by the same run. As a job it
 * survives the user leaving the app and waits for a connection instead of failing without one.
 */
class TrailPackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @Serializable
    private data class Release(val assets: List<Asset> = emptyList())

    @Serializable
    private data class Asset(
        val name: String = "",
        val size: Long = 0,
        @SerialName("updated_at") val updatedAt: String = "",
        @SerialName("browser_download_url") val url: String = "",
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val packs = TrailmapApp.packs(applicationContext)
        val started = System.currentTimeMillis()
        try {
            val assets = latestAssets()
            if (assets == null) {
                DiagLog.log("pack", "no packs published yet")
                packs.recordCheck()
                return@withContext Result.success()
            }
            assets[TrailPacks.INDEX_ASSET]?.let { a ->
                if (packs.index.value == null || packs.indexUpdatedAt != a.updatedAt) {
                    packs.saveIndex(fetchText(a.url), a.updatedAt)
                    DiagLog.log("pack", "index: ${packs.index.value?.states?.size} states")
                }
            }
            repeat(MAX_PASSES) {
                val todo = packs.selected.value.mapNotNull { slug ->
                    val a = assets[TrailPacks.assetFor(slug)] ?: return@mapNotNull null
                    val current = packs.hasState(slug) && packs.installedInfo(slug)?.updatedAt == a.updatedAt
                    if (current) null else slug to a
                }
                if (todo.isEmpty()) return@repeat
                val total = todo.sumOf { it.second.size }
                var before = 0L
                todo.forEachIndexed { i, (slug, a) ->
                    if (slug !in packs.selected.value) return@forEachIndexed // removed while queued
                    val name = packs.stateName(slug)
                    DiagLog.log("pack", "downloading $name, ${a.size / 1024} KB (${a.updatedAt})")
                    val part = packs.partFile(slug)
                    download(a, part) { done ->
                        setProgress(
                            workDataOf(
                                KEY_STATE to name, KEY_NUMBER to i + 1, KEY_COUNT to todo.size,
                                KEY_DONE to before + done, KEY_TOTAL to total,
                            ),
                        )
                    }
                    if (slug in packs.selected.value) packs.install(slug, part, TrailPacks.Installed(a.updatedAt, a.size))
                    else part.delete()
                    before += a.size
                }
            }
            packs.pruneUnselected()
            packs.retireLegacyIfReplaced()
            packs.recordCheck()
            DiagLog.log("pack", "sync done in ${System.currentTimeMillis() - started} ms")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.log("pack", "sync failed (attempt ${runAttemptCount + 1}): ${e.message}")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /** The release's assets by name, or null if the release doesn't exist yet. */
    private fun latestAssets(): Map<String, Asset>? {
        val req = Request.Builder().url(RELEASE_API)
            .header("User-Agent", "trailmap-android")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("release check HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("empty release response")
            return json.decodeFromString<Release>(body).assets.associateBy { it.name }
        }
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "trailmap-android").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("index download HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("empty index")
        }
    }

    private suspend fun download(asset: Asset, dest: java.io.File, progress: suspend (Long) -> Unit) {
        val req = Request.Builder().url(asset.url).header("User-Agent", "trailmap-android").build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("pack download HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty pack download")
                var done = 0L
                var reported = -1L
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            // Every ~256 KB: often enough to move the bar, rarely enough not to
                            // flood WorkManager's progress store.
                            if (done - reported >= 256 * 1024) {
                                reported = done
                                progress(done)
                            }
                        }
                    }
                }
                // Either direction means this isn't the file the release describes: cut short, or
                // — seen on the emulator while the weekly build was replacing the asset — the
                // download URL still serving last week's file. A retry gets the right one.
                if (asset.size > 0 && done != asset.size) {
                    throw IOException("pack size mismatch: got $done bytes, release lists ${asset.size}; retrying")
                }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    companion object {
        private const val UNIQUE = "trailpack"
        const val KEY_STATE = "state"
        const val KEY_NUMBER = "number"
        const val KEY_COUNT = "count"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val MAX_ATTEMPTS = 5
        private const val MAX_PASSES = 3
        private const val RELEASE_API = "https://api.github.com/repos/Pr0zak/trailmap/releases/tags/trailpack"
        private val CHECK_EVERY_MS = TimeUnit.HOURS.toMillis(24)

        private val json = Json { ignoreUnknownKeys = true }
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * On launch: sync if a chosen state is missing, the index has never been fetched, or the
         * last check was over a day ago. Packs are a few MB per state and are what makes the
         * map fast, so downloads don't wait for Wi-Fi.
         */
        fun enqueueIfDue(context: Context) {
            val packs = TrailmapApp.packs(context)
            val missing = packs.selected.value.any { !packs.hasState(it) }
            val stale = System.currentTimeMillis() - packs.checkedAt > CHECK_EVERY_MS
            if (missing || stale || packs.index.value == null) enqueue(context)
        }

        /**
         * Sync now. Appended behind a running sync rather than replacing it, so a state added
         * mid-download is fetched without restarting the one in flight.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrailPackWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** The current (or last) sync, for progress and status. */
        fun observe(context: Context): Flow<WorkInfo?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos ->
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: infos.firstOrNull { !it.state.isFinished }
                    ?: infos.lastOrNull()
            }
    }
}
