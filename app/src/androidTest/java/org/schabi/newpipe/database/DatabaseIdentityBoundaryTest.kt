package org.schabi.newpipe.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.OffsetDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedLastUpdatedEntity
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** Uses only a new in-memory Room database; no installed user's database is opened. */
class DatabaseIdentityBoundaryTest {
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun subscriptionLookupsTreatUrlWildcardsAndCaseAsLiteralIdentity() {
        db.subscriptionDAO().insertAll(
            listOf(
                subscription(1, 1, "/UCXA"),
                subscription(2, 1, "/UC_A"),
                subscription(3, 1, "/uc_a"),
                subscription(4, 2, "/UC_A"),
                subscription(5, 1, "/chan-other"),
                subscription(6, 1, "/chan%")
            )
        )

        assertEquals(
            listOf(2L),
            db.subscriptionDAO().getSubscriptionFlowable(1, "/UC_A")
                .firstOrError().blockingGet().map { it.uid }
        )
        assertEquals(2L, db.subscriptionDAO().getSubscription(1, "/UC_A").blockingGet()!!.uid)
        assertEquals(
            listOf(6L),
            db.subscriptionDAO().getSubscriptionFlowable(1, "/chan%")
                .firstOrError().blockingGet().map { it.uid }
        )
        assertEquals(4L, db.subscriptionDAO().getSubscription(2, "/UC_A").blockingGet()!!.uid)
    }

    @Test
    fun deletingOneSubscriptionPreservesOtherSubscriptionsAndTheirFeedLinks() {
        db.subscriptionDAO().insertAll(
            listOf(
                subscription(1, 1, "/UCXA"),
                subscription(2, 1, "/UC_A"),
                subscription(3, 1, "/uc_a"),
                subscription(4, 2, "/UC_A")
            )
        )
        db.streamDAO().insert(stream(10, "linked", "2023-01-01T00:00:00Z"))
        db.feedDAO().insertAll(listOf(FeedEntity(10, 1), FeedEntity(10, 2)))

        assertEquals(1, db.subscriptionDAO().deleteSubscription(1, "/UC_A"))
        assertEquals(listOf(1L, 3L, 4L), allSubscriptionIds())
        assertEquals(1, feedLinkCount(10, 1))
    }

    @Test
    fun upsertConflictUpdatesOnlyTheExactUrl() {
        db.subscriptionDAO().insertAll(
            listOf(
                subscription(1, 1, "/UCXA", "Neighbor"),
                subscription(2, 1, "/UC_A", "Original")
            )
        )

        val result = db.subscriptionDAO().upsertAll(
            listOf(subscription(0, 1, "/UC_A", "Updated"))
        )

        assertEquals(2L, result.single().uid)
        assertEquals("Neighbor", db.subscriptionDAO().getSubscription(1L).name)
        assertEquals("Updated", db.subscriptionDAO().getSubscription(2L).name)
    }

    @Test
    fun repeatedHistoryDoesNotConsumeTheEntireFeedLimit() {
        db.subscriptionDAO().insert(subscription(1, 1, "/channel"))
        db.streamDAO().insertAll(
            listOf(
                stream(10, "new", "2023-02-01T00:00:00Z"),
                stream(11, "old", "2023-01-01T00:00:00Z")
            )
        )
        db.feedDAO().insertAll(listOf(FeedEntity(10, 1), FeedEntity(11, 1)))
        val firstAccess = OffsetDateTime.parse("2023-02-01T00:00:00Z")
        db.streamHistoryDAO().insertAll(
            (0..500).map {
                StreamHistoryEntity(10, firstAccess.plusSeconds(it.toLong()), 1)
            }
        )

        assertEquals(listOf(10L, 11L), feedStreamIds())
    }

    @Test
    fun streamSharedByTwoSubscriptionsIsListedOnce() {
        db.subscriptionDAO().insertAll(
            listOf(subscription(1, 1, "/a"), subscription(2, 1, "/b"))
        )
        db.streamDAO().insert(stream(10, "shared", "2023-01-01T00:00:00Z"))
        db.feedDAO().insertAll(listOf(FeedEntity(10, 1), FeedEntity(10, 2)))

        assertEquals(listOf(10L), feedStreamIds())
    }

    @Test
    fun fiveHundredDistinctStreamsStillRespectTheFeedLimitAndOrder() {
        db.subscriptionDAO().insert(subscription(1, 1, "/channel"))
        val firstUpload = OffsetDateTime.parse("2023-01-01T00:00:00Z")
        db.streamDAO().insertAll(
            (1L..501L).map {
                stream(it, "stream-$it", firstUpload.plusSeconds(it).toString())
            }
        )
        db.feedDAO().insertAll((1L..501L).map { FeedEntity(it, 1) })

        val ids = feedStreamIds()
        assertEquals(500, ids.size)
        assertEquals(500, ids.toSet().size)
        assertEquals(501L, ids.first())
        assertEquals(2L, ids.last())
    }

    @Test
    fun sharedStreamCleanupPreservesTheOtherSubscriptionsLastLink() {
        db.subscriptionDAO().insertAll(
            listOf(subscription(1, 1, "/a"), subscription(2, 1, "/b"))
        )
        db.streamDAO().insertAll(
            listOf(
                stream(10, "shared", "2023-01-01T00:00:00Z"),
                stream(11, "newer", "2023-02-01T00:00:00Z")
            )
        )
        db.feedDAO().insertAll(
            listOf(FeedEntity(10, 1), FeedEntity(10, 2), FeedEntity(11, 2))
        )

        db.feedDAO().unlinkStreamsOlderThan(OffsetDateTime.parse("2023-03-01T00:00:00Z"))

        assertEquals(1, feedLinkCount(10, 1))
        assertEquals(0, feedLinkCount(10, 2))
        assertEquals(1, feedLinkCount(11, 2))
    }

    @Test
    fun subscriptionsWithoutMarkersCountAsNotLoaded() {
        assertEquals(0L, db.feedDAO().notLoadedCount().firstOrError().blockingGet())
        db.subscriptionDAO().insertAll(
            listOf(
                subscription(1, 1, "/missing"),
                subscription(2, 1, "/null"),
                subscription(3, 1, "/updated")
            )
        )
        db.feedDAO().setLastUpdatedForSubscription(FeedLastUpdatedEntity(2, null))
        db.feedDAO().setLastUpdatedForSubscription(
            FeedLastUpdatedEntity(3, OffsetDateTime.parse("2023-01-01T00:00:00Z"))
        )

        assertEquals(2L, db.feedDAO().notLoadedCount().firstOrError().blockingGet())
        assertEquals(
            setOf(1L, 2L),
            db.feedDAO().getAllOutdated(OffsetDateTime.parse("2022-01-01T00:00:00Z"))
                .firstOrError().blockingGet().map { it.uid }.toSet()
        )
    }

    private fun feedStreamIds(): List<Long> = db.feedDAO().getStreams(
        FeedGroupEntity.GROUP_ALL_ID,
        includePlayed = true,
        includePartiallyPlayed = true,
        null
    ).blockingGet()!!.map { it.stream.uid }

    private fun allSubscriptionIds(): List<Long> = db.subscriptionDAO().getAll()
        .firstOrError().blockingGet().map { it.uid }.sorted()

    private fun feedLinkCount(streamId: Long, subscriptionId: Long): Int {
        val sql = "SELECT COUNT(*) FROM feed WHERE stream_id = $streamId " +
            "AND subscription_id = $subscriptionId"
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun subscription(uid: Long, serviceId: Int, url: String, name: String = url) = SubscriptionEntity(uid = uid, serviceId = serviceId, url = url, name = name)

    private fun stream(uid: Long, title: String, uploaded: String) = StreamEntity(
        uid = uid,
        serviceId = 1,
        url = "/watch/$uid",
        title = title,
        streamType = StreamType.VIDEO_STREAM,
        duration = 60,
        uploader = "Uploader",
        uploadDate = OffsetDateTime.parse(uploaded)
    )
}
