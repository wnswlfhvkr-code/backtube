package org.schabi.newpipe.local.feed

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.xwray.groupie.GroupieAdapter
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.feed.item.StreamItem
import org.schabi.newpipe.testUtil.TestDatabase

/** Synthetic rows and in-memory Room only; no installed subscriptions are opened. */
class FeedBoundaryDeviceTest {
    private lateinit var database: AppDatabase

    @Before
    fun createOwnedDatabase() {
        database = TestDatabase.createReplacingNewPipeDatabase()
    }

    @After
    fun closeOwnedDatabase() {
        NewPipeDatabase.close()
    }

    @Test
    fun shrinkingFeedToEmptyOrOneRowCannotReverseHighlightRange() {
        val fragment = FeedFragment()
        val adapter = GroupieAdapter()
        set(fragment, "groupAdapter", adapter)
        val updateTime = OffsetDateTime.now(ZoneOffset.UTC)
        val notifyCounts = mutableListOf<Int>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                notifyCounts.add(itemCount)
            }
        })

        set(fragment, "lastNewItemsCount", 3)
        highlight(fragment, updateTime)
        assertEquals(0, getInt(fragment, "lastNewItemsCount"))

        val oldStream = StreamEntity(
            serviceId = 0,
            url = "https://example.test/old",
            title = "Old",
            streamType = StreamType.VIDEO_STREAM,
            duration = 20,
            uploader = "Fixture",
            uploadDate = updateTime.minusDays(1)
        )
        adapter.add(StreamItem(StreamWithState(oldStream, null)))
        set(fragment, "lastNewItemsCount", 3)
        highlight(fragment, updateTime)
        assertEquals(0, getInt(fragment, "lastNewItemsCount"))
        assertEquals(1, notifyCounts.last())
    }

    @Test
    fun undatedAudioLiveRemainsInFeedWhileUndatedOrdinaryAudioDoesNot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database.subscriptionDAO().insert(
            SubscriptionEntity(
                uid = 1,
                serviceId = 0,
                url = "https://example.test/channel",
                name = "Fixture"
            )
        )
        val manager = FeedDatabaseManager(context)
        val videoLive = item("video-live", StreamType.LIVE_STREAM)
        val audioLive = item("audio-live", StreamType.AUDIO_LIVE_STREAM)
        val normalAudio = item("normal-audio", StreamType.AUDIO_STREAM)
        val recentAudio = item("recent-audio", StreamType.AUDIO_STREAM).apply {
            uploadDate = DateWrapper(OffsetDateTime.now(ZoneOffset.UTC), false)
        }
        val oldAudioLive = item("old-audio-live", StreamType.AUDIO_LIVE_STREAM).apply {
            uploadDate = DateWrapper(FeedDatabaseManager.FEED_OLDEST_ALLOWED_DATE.minusDays(1), false)
        }

        manager.upsertAll(1, listOf(videoLive, audioLive, normalAudio, recentAudio, oldAudioLive))

        val feedUrls = requireNotNull(
            manager.getStreams(
                FeedGroupEntity.GROUP_ALL_ID,
                includePlayedStreams = true,
                includePartiallyPlayedStreams = true,
                includeFutureStreams = true
            ).blockingGet()
        ).map { it.stream.url }
        assertEquals(setOf(videoLive.url, audioLive.url, recentAudio.url), feedUrls.toSet())
        assertEquals(3, feedUrls.size)
    }

    private fun item(id: String, type: StreamType) = StreamInfoItem(
        0,
        "https://example.test/$id",
        id,
        type
    ).apply { uploaderName = "Fixture" }

    private fun set(target: FeedFragment, name: String, value: Any) {
        FeedFragment::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun getInt(target: FeedFragment, name: String) = FeedFragment::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(target)

    private fun highlight(target: FeedFragment, updateTime: OffsetDateTime) {
        FeedFragment::class.java.getDeclaredMethod(
            "highlightNewItemsAfter",
            OffsetDateTime::class.java
        ).apply {
            isAccessible = true
            invoke(target, updateTime)
        }
    }
}
