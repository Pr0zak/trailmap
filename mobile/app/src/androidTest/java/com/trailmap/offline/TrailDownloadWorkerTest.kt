package com.trailmap.offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.trailmap.TrailmapApp
import com.trailmap.data.GeoPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Queues a real one-section trail download through [TrailDownloads] and waits for the job to
 * finish on the device — the path "Get trails" takes. Needs a network connection.
 */
@RunWith(AndroidJUnit4::class)
class TrailDownloadWorkerTest {
    @Test fun queuedDownloadRunsToCompletion(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A small circle in downtown Kansas City keeps the Overpass query cheap.
        val center = GeoPoint(39.0997, -94.5786)
        TrailDownloads.enqueue(context, "Test area", com.trailmap.data.ViewBounds(39.11, 39.09, -94.56, -94.59, 12.0), listOf(center), 3000, mtb = false, needed = 1)
        val done = withTimeout(150_000) {
            TrailDownloads.observe(context).first { infos -> infos.isNotEmpty() && infos.all { it.state.isFinished } }
        }.last()
        assertEquals(WorkInfo.State.SUCCEEDED, done.state)
        val message = done.outputData.getString(TrailDownloads.KEY_MESSAGE).orEmpty()
        assertTrue(message, message.startsWith("Trails saved"))
        assertTrue("section not in the offline store", TrailmapApp.overpass(context).hasSavedArea(center, 3000, false))
        WorkManager.getInstance(context).pruneWork()
        Unit
    }

    @Test fun queuedJobsKeepTheirOwnNames(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WorkManager.getInstance(context).pruneWork()
        // Two small areas queued back to back; the second must finish under its own name.
        val a = GeoPoint(39.0997, -94.5786)
        val b = GeoPoint(39.0350, -94.5550)
        TrailDownloads.enqueue(context, "Area A", com.trailmap.data.ViewBounds(39.11, 39.09, -94.56, -94.59, 12.0), listOf(a), 3000, mtb = false, needed = 1)
        TrailDownloads.enqueue(context, "Area B", com.trailmap.data.ViewBounds(39.05, 39.02, -94.54, -94.57, 12.0), listOf(b), 3000, mtb = false, needed = 1)
        // The same ground again is not queued twice.
        val again = TrailDownloads.enqueue(context, "Area B again", com.trailmap.data.ViewBounds(39.05, 39.02, -94.54, -94.57, 12.0), listOf(b), 3000, mtb = false, needed = 1)
        assertTrue("duplicate was queued", !again)
        val infos = withTimeout(240_000) {
            TrailDownloads.observe(context).first { l -> l.size >= 2 && l.all { it.state.isFinished } }
        }
        val names = infos.map { it.outputData.getString(TrailDownloads.KEY_RESULT_AREA) }.toSet()
        assertEquals(setOf("Area A", "Area B"), names)
        WorkManager.getInstance(context).pruneWork()
        Unit
    }
}
