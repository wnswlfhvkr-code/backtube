package org.schabi.newpipe.local.subscription.workers

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.testUtil.TestDatabase

/** Real workers, test-only content provider, and in-memory Room; no account or network. */
@RunWith(AndroidJUnit4::class)
class SubscriptionWorkerBoundaryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private var emptyFile: File? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor()).build()
        )
        database = TestDatabase.createReplacingNewPipeDatabase()
    }

    @After
    fun tearDown() {
        emptyFile?.delete()
        NewPipeDatabase.close()
    }

    @Test
    fun initialForegroundCanBeRequestedTwiceBeforeImportStarts() = runBlocking {
        val worker = importWorker(NULL_STREAM_URI)
        assertNotNull(worker.getForegroundInfo().notification)
        assertNotNull(worker.getForegroundInfo().notification)
        val subscriptionsField = SubscriptionImportWorker::class.java
            .getDeclaredField("subscriptions")
        subscriptionsField.isAccessible = true
        subscriptionsField.set(worker, listOf(SubscriptionItem(1, "https://fixture.invalid/a", "A")))
        assertEquals(
            1,
            worker.getForegroundInfo().notification.extras
                .getInt(Notification.EXTRA_PROGRESS_MAX)
        )
        assertTrue(database.subscriptionDAO().getAll().blockingFirst().isEmpty())
    }

    @Test
    fun nullImportStreamFailsWithoutAddingRows() = runBlocking {
        assertEquals(ListenableWorker.Result.failure(), importWorker(NULL_STREAM_URI).doWork())
        assertTrue(database.subscriptionDAO().getAll().blockingFirst().isEmpty())
    }

    @Test
    fun actualEmptyArchiveStillSucceeds() = runBlocking {
        emptyFile = File(context.cacheDir, "subscription-empty-${System.nanoTime()}.json")
        emptyFile!!.outputStream().use { ImportExportJsonHelper.writeTo(emptyList(), it) }
        assertEquals(
            ListenableWorker.Result.success(),
            importWorker(emptyFile!!.toURI().toString()).doWork()
        )
        assertTrue(database.subscriptionDAO().getAll().blockingFirst().isEmpty())
    }

    @Test
    fun nullExportStreamFailsRatherThanReportingSuccess() = runBlocking {
        val worker = TestListenableWorkerBuilder.from(context, SubscriptionExportWorker::class.java)
            .setInputData(workDataOf("exportPath" to NULL_STREAM_URI)).build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
        assertTrue(database.subscriptionDAO().getAll().blockingFirst().isEmpty())
    }

    @Test
    fun actualEmptyExportStillWritesAReadableArchive() = runBlocking {
        emptyFile = File(context.cacheDir, "subscription-export-${System.nanoTime()}.json")
        val worker = TestListenableWorkerBuilder.from(context, SubscriptionExportWorker::class.java)
            .setInputData(workDataOf("exportPath" to emptyFile!!.toURI().toString())).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(
            emptyList<SubscriptionItem>(),
            emptyFile!!.inputStream().use { ImportExportJsonHelper.readFrom(it) }
        )
    }

    private fun importWorker(uri: String) = TestListenableWorkerBuilder.from(context, SubscriptionImportWorker::class.java)
        .setInputData(SubscriptionImportInput.PreviousExportMode(uri).toData()).build()

    companion object {
        private const val NULL_STREAM_URI = "content://org.schabi.newpipe.test.nullsubscriptionstream/null"
    }
}
