package com.trailmap.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trailmap.MainActivity
import com.trailmap.R
import com.trailmap.TrailmapApp
import com.trailmap.data.DiagLog
import com.trailmap.data.GeoPoint
import com.trailmap.data.OverpassClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * Offline trail downloads, run as WorkManager jobs rather than in the ViewModel.
 *
 * In the ViewModel a download only ran while the Offline screen was on screen. Device logs
 * showed it cut off mid-area again and again: leaving the app or letting the screen lock takes
 * the app's network away, every mirror's name lookup starts failing, and the download stopped
 * with "no connection". As a job it keeps going in the background with a progress notification,
 * and when the connection really does drop it stops, and WorkManager restarts it once the
 * network is back — the sections already saved are skipped, so it carries on where it was.
 */
object TrailDownloads {
    private const val UNIQUE = "trail-downloads"

    const val KEY_AREA = "area"
    const val KEY_LATS = "lats"
    const val KEY_LONS = "lons"
    const val KEY_RADIUS = "radius"
    const val KEY_MTB = "mtb"
    const val KEY_NEEDED = "needed"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_NOTE = "note"
    const val KEY_MESSAGE = "message"

    /**
     * Queue a download of [circles] (each [radiusMeters] wide) for [area]. Areas queue up and
     * run one at a time; the job waits for a network connection before it starts.
     * [needed] is how many circles the whole box would take, when [circles] is a capped subset.
     */
    fun enqueue(context: Context, area: String, circles: List<GeoPoint>, radiusMeters: Int, mtb: Boolean, needed: Int) {
        val request = OneTimeWorkRequestBuilder<TrailDownloadWorker>()
            .setInputData(
                workDataOf(
                    KEY_AREA to area,
                    KEY_LATS to circles.map { it.lat }.toDoubleArray(),
                    KEY_LONS to circles.map { it.lon }.toDoubleArray(),
                    KEY_RADIUS to radiusMeters,
                    KEY_MTB to mtb,
                    KEY_NEEDED to needed,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE)
            .build()
        DiagLog.log("offline", "queued trails for $area: ${circles.size} sections")
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Stop the running download and drop the queue. Sections already saved stay saved. */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }

    /** Every job in the queue, for the Offline screen's status card. */
    fun observe(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE)
}

class TrailDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val area = inputData.getString(TrailDownloads.KEY_AREA) ?: "This area"

    override suspend fun doWork(): Result {
        val lats = inputData.getDoubleArray(TrailDownloads.KEY_LATS) ?: return Result.failure()
        val lons = inputData.getDoubleArray(TrailDownloads.KEY_LONS) ?: return Result.failure()
        val circles = lats.indices.map { GeoPoint(lats[it], lons[it]) }
        val radius = inputData.getInt(TrailDownloads.KEY_RADIUS, 0)
        val mtb = inputData.getBoolean(TrailDownloads.KEY_MTB, false)
        val needed = inputData.getInt(TrailDownloads.KEY_NEEDED, circles.size)
        val overpass = TrailmapApp.overpass(applicationContext)
        val total = circles.size

        report(0, total, null)
        var saved = 0
        var failed = 0
        var lastError: String? = null
        for ((i, c) in circles.withIndex()) {
            if (overpass.hasArea(c, radius, mtb)) {
                saved++
                report(i + 1, total, null)
                continue
            }
            // Busy public mirrors answer these multi-megabyte pulls with 504s in bursts, and
            // the same section often goes through a few seconds later. Wait and retry before
            // giving up on it; move on to the next section after that.
            var attempt = 0
            while (true) {
                val e = try {
                    overpass.prefetch(c, radius, mtb)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                if (e == null) {
                    saved++
                    break
                }
                lastError = e.message
                if (e is OverpassClient.NoConnection) {
                    // The phone is offline. Hand back to WorkManager, which re-runs this job
                    // once the network constraint holds again; saved sections are skipped.
                    if (runAttemptCount >= MAX_RUNS) {
                        DiagLog.log("offline", "$area: gave up after $MAX_RUNS connection losses, $saved/$total saved")
                        return Result.failure(message("Stopped: the connection kept dropping. $saved of $total sections saved; tap Get trails to carry on."))
                    }
                    DiagLog.log("offline", "$area: connection lost at section ${i + 1}/$total, resuming when it's back")
                    return Result.retry()
                }
                if (attempt >= RETRY_DELAYS_MS.size) {
                    failed++
                    DiagLog.log("offline", "$area: section ${i + 1}/$total skipped after ${attempt + 1} tries: ${e.message}")
                    break
                }
                val wait = RETRY_DELAYS_MS[attempt++]
                DiagLog.log("offline", "$area: section ${i + 1}/$total failed (${e.message}), retrying in ${wait / 1000} s")
                report(i, total, "Servers busy, trying section ${i + 1} again in ${wait / 1000} s")
                delay(wait)
            }
            report(i + 1, total, null)
        }

        val text = when {
            failed == 0 && needed > total ->
                "Trails saved for the centre of this area ($total of $needed sections). Download a city region for full coverage."
            failed == 0 -> "Trails saved for offline use."
            saved > 0 -> "$saved of $total sections saved. OpenStreetMap was too busy for the other $failed. Tap Get trails to try those again."
            else -> "Couldn't download trails: ${lastError ?: "network error"}"
        }
        DiagLog.log("offline", "$area: done, $saved/$total saved, $failed skipped")
        return Result.success(message(text))
    }

    private fun message(text: String) = workDataOf(TrailDownloads.KEY_MESSAGE to text, TrailDownloads.KEY_AREA to area)

    /** Progress for the Offline screen, and the notification that keeps the job running. */
    private suspend fun report(done: Int, total: Int, note: String?) {
        setProgress(
            workDataOf(
                TrailDownloads.KEY_AREA to area,
                TrailDownloads.KEY_DONE to done,
                TrailDownloads.KEY_TOTAL to total,
                TrailDownloads.KEY_NOTE to note,
            ),
        )
        // Android refuses to start a foreground service from the background (a job resumed
        // after the network came back, with the app closed). The job still runs then, just
        // without the notification, so that refusal is not a failure.
        try {
            setForeground(foregroundInfo(done, total, note))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null)

    private fun foregroundInfo(done: Int, total: Int, note: String?): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Offline downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of trail downloads for offline use"
                },
            )
        }
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Downloading trails for $area")
            .setContentText(note ?: if (total > 0) "Section $done of $total" else "Starting…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "offline_downloads"
        private const val NOTIFICATION_ID = 4101

        /** Waits before re-trying a section that failed on busy mirrors. */
        private val RETRY_DELAYS_MS = longArrayOf(12_000L, 25_000L)

        /** How many times a job may be restarted after losing the connection. */
        private const val MAX_RUNS = 8
    }
}
