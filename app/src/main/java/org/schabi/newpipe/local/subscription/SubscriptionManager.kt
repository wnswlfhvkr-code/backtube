package org.schabi.newpipe.local.subscription

import android.content.Context
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.database.subscription.SubscriptionDAO
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.feed.service.FeedUpdateInfo
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.image.ImageStrategy

class SubscriptionManager(context: Context) {
    private val database = NewPipeDatabase.getInstance(context)
    private val subscriptionTable = database.subscriptionDAO()
    private val feedDatabaseManager = FeedDatabaseManager(context)

    companion object {
        // Channel and settings screens own different managers but can update the same channel.
        // Retain one sequence per touched channel for this process so delayed work cannot revive
        // an older request after a newer one completes.
        private val notificationModeRequests = ConcurrentHashMap<Pair<Int, String>, AtomicLong>()
    }

    fun subscriptionTable(): SubscriptionDAO = subscriptionTable
    fun subscriptions() = subscriptionTable.getAll()

    fun getSubscriptions(
        currentGroupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        filterQuery: String = "",
        showOnlyUngrouped: Boolean = false
    ): Flowable<List<SubscriptionEntity>> {
        return when {
            filterQuery.isNotEmpty() -> {
                return if (showOnlyUngrouped) {
                    subscriptionTable.getSubscriptionsOnlyUngroupedFiltered(
                        currentGroupId,
                        filterQuery
                    )
                } else {
                    subscriptionTable.getSubscriptionsFiltered(filterQuery)
                }
            }

            showOnlyUngrouped -> subscriptionTable.getSubscriptionsOnlyUngrouped(currentGroupId)

            else -> subscriptionTable.getAll()
        }
    }

    fun upsertAll(infoList: List<Pair<ChannelInfo, ChannelTabInfo?>>) {
        val listEntities = infoList.map { SubscriptionEntity.from(it.first) }
        subscriptionTable.upsertAll(listEntities)

        database.runInTransaction {
            infoList.forEachIndexed { index, info ->
                // There may be no tabs on the channel to refresh the feed from
                if (info.second == null) return@forEachIndexed

                val streams = info.second!!.relatedItems.filterIsInstance<StreamInfoItem>()
                feedDatabaseManager.upsertAll(listEntities[index].uid, streams)
            }
        }
    }

    fun updateChannelInfo(info: ChannelInfo): Completable = subscriptionTable.getSubscription(info.serviceId, info.url)
        .flatMapCompletable {
            Completable.fromRunnable {
                it.apply {
                    name = info.name
                    avatarUrl = ImageStrategy.imageListToDbUrl(info.avatars)
                    description = info.description
                    subscriberCount = info.subscriberCount
                }
                subscriptionTable.update(it)
            }
        }

    fun updateNotificationMode(serviceId: Int, url: String, @NotificationMode mode: Int): Completable {
        // The UI can issue a newer request while an older channel fetch is still pending.
        // Assign the order at the call site, before callers schedule subscriptions on IO.
        val sequence = notificationModeRequests.getOrPut(serviceId to url) { AtomicLong() }
        val requestId = synchronized(sequence) { sequence.incrementAndGet() }
        return subscriptionTable().getSubscription(serviceId, url)
            .flatMapCompletable { entity: SubscriptionEntity ->
                if (sequence.get() != requestId) {
                    Completable.complete()
                } else {
                    val update = Completable.fromAction {
                        synchronized(sequence) {
                            if (sequence.get() == requestId) {
                                // A feed refresh can change other columns during the baseline
                                // fetch. Update only this column, not the stale entity snapshot.
                                subscriptionTable().updateNotificationMode(serviceId, url, mode)
                            }
                        }
                    }
                    if (mode != NotificationMode.DISABLED &&
                        entity.notificationMode == NotificationMode.DISABLED
                    ) {
                        // Publish the new mode only after existing streams have been remembered.
                        rememberAllStreams(entity).andThen(update)
                    } else {
                        update
                    }
                }
            }
    }

    fun updateFromInfo(info: FeedUpdateInfo) {
        val subscriptionEntity = subscriptionTable.getSubscription(info.uid)

        subscriptionEntity.name = info.name

        // some services do not provide an avatar URL
        info.avatarUrl?.let { subscriptionEntity.avatarUrl = it }

        // these two fields are null if the feed info was fetched using the fast feed method
        info.description?.let { subscriptionEntity.description = it }
        info.subscriberCount?.let { subscriptionEntity.subscriberCount = it }

        subscriptionTable.update(subscriptionEntity)
    }

    fun deleteSubscription(serviceId: Int, url: String): Completable {
        return Completable.fromCallable { subscriptionTable.deleteSubscription(serviceId, url) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun insertSubscription(subscriptionEntity: SubscriptionEntity) {
        subscriptionTable.insert(subscriptionEntity)
    }

    fun deleteSubscription(subscriptionEntity: SubscriptionEntity) {
        subscriptionTable.delete(subscriptionEntity)
    }

    /**
     * Fetches the list of videos for the provided channel and saves them in the database, so that
     * they will be considered as "old"/"already seen" streams and the user will never be notified
     * about any one of them.
     */
    private fun rememberAllStreams(subscription: SubscriptionEntity): Completable {
        return ExtractorHelper.getChannelInfo(subscription.serviceId, subscription.url, false)
            .flatMap { info ->
                ExtractorHelper.getChannelTab(subscription.serviceId, info.tabs.first(), false)
            }
            .map { channel -> channel.relatedItems.filterIsInstance<StreamInfoItem>().map { stream -> StreamEntity(stream) } }
            .flatMapCompletable { entities ->
                Completable.fromAction {
                    database.streamDAO().upsertAll(entities)
                }
            }.onErrorComplete()
    }
}
