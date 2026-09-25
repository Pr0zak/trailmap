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
        TrailDownloads.enqueue(context, "Test area", listOf(center), 3000, mtb = false, needed = 1)
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
}
