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
import com.trailmap.data.TrailPack
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
 * Keeps the regional trail pack ([TrailPack]) installed and current.
 *
 * The pack is published as the `trailpack.zip` asset of the `trailpack` GitHub release, rebuilt
 * weekly by `.github/workflows/trailpack.yml`. That release is a prerelease, so the app updater
 * — which reads `/releases/latest` — never mistakes it for an app version.
 *
 * Runs as a job so an 8-10 MB download survives the user leaving the app, and so it waits for a
 * connection instead of failing without one. It checks the release at most once a day
 * ([enqueueIfDue]) and downloads only when the asset has changed.
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
        val pack = TrailmapApp.pack(applicationContext)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val now = System.currentTimeMillis()
        try {
            val asset = latestAsset()
            if (asset == null) {
                DiagLog.log("pack", "no pack published yet")
                pack.recordCheck((pack.installed() ?: TrailPack.Installed()).copy(checkedAt = now))
                return@withContext Result.success()
            }
            val installed = pack.installed()
            if (!force && pack.ready && installed?.updatedAt == asset.updatedAt) {
                pack.recordCheck(installed.copy(checkedAt = now))
                DiagLog.log("pack", "up to date (${asset.updatedAt})")
                return@withContext Result.success()
            }
            DiagLog.log("pack", "downloading ${asset.size / 1024} KB (${asset.updatedAt})")
            val part = pack.partFile()
            download(asset, part)
            pack.install(part, TrailPack.Installed(asset.updatedAt, asset.size, now))
            DiagLog.log("pack", "installed in ${System.currentTimeMillis() - now} ms")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.log("pack", "update failed (attempt ${runAttemptCount + 1}): ${e.message}")
            pack.partFile().delete()
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun latestAsset(): Asset? {
        val req = Request.Builder().url(RELEASE_API)
            .header("User-Agent", "trailmap-android")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("release check HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("empty release response")
            return json.decodeFromString<Release>(body).assets.firstOrNull { it.name == TrailPack.FILE_NAME }
        }
    }

    private suspend fun download(asset: Asset, dest: java.io.File) {
        val req = Request.Builder().url(asset.url).header("User-Agent", "trailmap-android").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("pack download HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty pack download")
            val total = body.contentLength().takeIf { it > 0 } ?: asset.size
            var done = 0L
            var reported = -1
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != reported) {
                            reported = pct
                            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                        }
                    }
                }
            }
            if (asset.size > 0 && done != asset.size) throw IOException("pack download cut short ($done of ${asset.size} bytes)")
        }
    }

    companion object {
        private const val UNIQUE = "trailpack"
        private const val KEY_FORCE = "force"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val MAX_ATTEMPTS = 5
        private const val RELEASE_API = "https://api.github.com/repos/Pr0zak/trailmap/releases/tags/trailpack"
        private val CHECK_EVERY_MS = TimeUnit.HOURS.toMillis(24)

        private val json = Json { ignoreUnknownKeys = true }
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * On launch: fetch the pack if it isn't installed, otherwise look for a newer one once a
         * day. The pack is small (~8.5 MB for Kansas + Missouri) and it is what makes the map
         * fast, so the first download doesn't wait for Wi-Fi.
         */
        fun enqueueIfDue(context: Context) {
            val pack = TrailmapApp.pack(context)
            val checked = pack.installed()?.checkedAt ?: 0L
            if (pack.ready && System.currentTimeMillis() - checked < CHECK_EVERY_MS) return
            enqueue(context, force = false)
        }

        /** Download now, even if the installed pack is current (the Offline screen's button). */
        fun enqueue(context: Context, force: Boolean) {
            val request = OneTimeWorkRequestBuilder<TrailPackWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_FORCE to force))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /** The current (or last) pack job, for the Offline screen's status line. */
        fun observe(context: Context): Flow<WorkInfo?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { it.lastOrNull() }
    }
}
