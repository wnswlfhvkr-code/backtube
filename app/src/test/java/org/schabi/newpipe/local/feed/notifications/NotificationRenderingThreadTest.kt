package org.schabi.newpipe.local.feed.notifications

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.local.feed.service.FeedUpdateInfo

/** Synthetic notification side effects; no OS notification or image request. */
class NotificationRenderingThreadTest {
    @After
    fun resetScheduler() {
        RxJavaPlugins.reset()
    }

    @Test
    fun renderingRunsOnIoAndKeepsOrderedItems() {
        val executor = Executors.newSingleThreadExecutor { command ->
            Thread(command, "synthetic-feed-io")
        }
        try {
            RxJavaPlugins.setIoSchedulerHandler { Schedulers.from(executor) }
            val first = update("first")
            val second = update("second")
            val rendered = mutableListOf<String>()
            val threads = mutableListOf<String>()

            val result = renderFeedNotifications(Single.just(listOf(first, second))) {
                rendered.add(it.name)
                threads.add(Thread.currentThread().name)
            }.blockingGet()

            assertEquals(listOf(first, second), result)
            assertEquals(listOf("first", "second"), rendered)
            assertTrue(threads.all { it == "synthetic-feed-io" })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun emptyAndFailedInputDoNotRender() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        var renderCount = 0

        assertEquals(
            emptyList<FeedUpdateInfo>(),
            renderFeedNotifications(Single.just(emptyList())) { renderCount++ }.blockingGet()
        )
        renderFeedNotifications(Single.error<List<FeedUpdateInfo>>(IllegalStateException("fixture"))) {
            renderCount++
        }.test().assertError(IllegalStateException::class.java)
        assertEquals(0, renderCount)
    }

    @Test
    fun disposalBeforeIoDeliverySkipsRendering() {
        val io = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { io }
        var rendered = false
        val observer = renderFeedNotifications(Single.just(listOf(update("later")))) {
            rendered = true
        }.test()

        observer.dispose()
        io.triggerActions()
        assertFalse(rendered)
    }

    @Test
    fun renderingFailureReachesErrorObserver() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        renderFeedNotifications(Single.just(listOf(update("broken")))) {
            throw IllegalStateException("synthetic render failure")
        }.test().assertError(IllegalStateException::class.java)
    }

    private fun update(name: String) = FeedUpdateInfo(
        uid = name.hashCode().toLong(),
        notificationMode = 0,
        name = name,
        avatarUrl = null,
        url = "https://example.test/$name",
        serviceId = 0,
        description = null,
        subscriberCount = null,
        streams = emptyList(),
        errors = emptyList()
    )
}
