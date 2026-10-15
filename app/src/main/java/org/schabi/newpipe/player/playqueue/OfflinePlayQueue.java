package org.schabi.newpipe.player.playqueue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.offline.OfflineMediaDuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Finite saved-file snapshot, including when all its entries have been removed. */
public final class OfflinePlayQueue extends PlayQueue {
    public OfflinePlayQueue(final OfflineStore store, final String uploader) throws IOException {
        this(savedItems(store, uploader), 0);
    }

    public OfflinePlayQueue(final List<StreamInfoItem> items, final int index) {
        super(index, localItems(items));
        if (index < 0 || (!items.isEmpty() && index >= items.size())) {
            throw new IllegalArgumentException("Invalid saved queue selection");
        }
    }

    private static List<PlayQueueItem> localItems(final List<StreamInfoItem> items) {
        if (items.stream().anyMatch(item -> !isLocal(item.getUrl()))) {
            throw new IllegalArgumentException("Saved playback requires local identities");
        }
        return items.stream().map(PlayQueueItem::new).collect(Collectors.toList());
    }

    private static List<StreamInfoItem> savedItems(final OfflineStore store,
                                                  final String uploader) throws IOException {
        final List<StreamInfoItem> items = new ArrayList<>();
        for (final OfflineStore.Entry entry : store.list()) {
            if (entry.state != OfflineStore.State.READY) {
                continue;
            }
            final StreamInfoItem item = new StreamInfoItem(entry.serviceId, entry.localUrl(),
                    entry.title, entry.mime.startsWith("video/")
                    ? StreamType.VIDEO_STREAM : StreamType.AUDIO_STREAM);
            item.setUploaderName(uploader);
            item.setDuration(OfflineMediaDuration.readSeconds(store.file(entry)));
            items.add(item);
        }
        return items;
    }

    private static boolean isLocal(final String url) {
        return url != null && url.startsWith(OfflineStore.LOCAL_PREFIX);
    }

    @Override public boolean isOfflineOnly() {
        return true;
    }

    @Override public boolean isComplete() {
        return true;
    }

    @Override public void fetch() {
        // The saved snapshot never has another page or a remote recommendation.
    }

    @Override public synchronized void append(@NonNull final List<PlayQueueItem> items) {
        if (!items.isEmpty() && items.stream().allMatch(item -> isLocal(item.getUrl()))) {
            super.append(items);
        }
    }

    @Override public void enqueueNext(@NonNull final PlayQueueItem item, final boolean skipIfSame) {
        if (isLocal(item.getUrl())) {
            super.enqueueNext(item, skipIfSame);
        }
    }

    @Override public synchronized boolean enqueueNextAndSelect(@NonNull final PlayQueueItem item) {
        return isLocal(item.getUrl()) && super.enqueueNextAndSelect(item);
    }

    // Timeline indices can become stale as deleted predecessors are removed.
    public synchronized int reconcileTransition(final String localUrl, final OfflineStore store,
                                                 final boolean includeCurrent) throws IOException {
        return reconcileTransition(localUrl, store, includeCurrent, false);
    }

    public synchronized int reconcileTransition(final String localUrl, final OfflineStore store,
                                                 final boolean includeCurrent,
                                                 final boolean repeatAll) throws IOException {
        final PlayQueueItem target = includeCurrent
                ? transitionTarget(localUrl, store, repeatAll) : itemWithUrl(localUrl);
        if (target != null && indexOf(target) != getIndex()) {
            setIndex(indexOf(target));
        }
        pruneUnavailable(store, includeCurrent);
        return isEmpty() || (includeCurrent && target == null) ? -1 : getIndex();
    }

    @Nullable
    private PlayQueueItem itemWithUrl(final String localUrl) {
        for (int i = 0; i < size(); i++) {
            if (getItem(i).getUrl().equals(localUrl)) {
                return getItem(i);
            }
        }
        return null;
    }

    @Nullable
    public synchronized PlayQueueItem transitionTarget(final String localUrl,
                                                        final OfflineStore store,
                                                        final boolean repeatAll)
            throws IOException {
        // Missing metadata may use the current identity; an unknown tag must never impersonate it.
        final PlayQueueItem target = localUrl == null ? getItem() : itemWithUrl(localUrl);
        if (target == null) {
            return null;
        }
        if (store.find(target.getServiceId(), target.getUrl()) != null) {
            return target;
        }
        final int index = indexOf(target);
        return availableAfter(index, repeatAll ? size() - 1 : size() - index - 1, store);
    }

    @Nullable
    public synchronized PlayQueueItem completionSuccessor(final OfflineStore store,
                                                           final boolean repeatAll)
            throws IOException {
        final int count = repeatAll ? size() : size() - getIndex() - 1;
        return availableAfter(getIndex(), count, store);
    }

    @Nullable
    public synchronized PlayQueueItem failureSuccessor(final OfflineStore store,
                                                        final boolean repeatAll)
            throws IOException {
        return availableAfter(getIndex(), repeatAll ? size() - 1 : size() - getIndex() - 1,
                store);
    }

    public synchronized boolean discardFailedCurrent(final OfflineStore store,
                                                       final boolean repeatAll) throws IOException {
        final PlayQueueItem failed = getItem();
        final PlayQueueItem next = failureSuccessor(store, repeatAll);
        if (next != null) {
            setIndex(indexOf(next));
        }
        remove(indexOf(failed));
        pruneUnavailable(store, true);
        return next != null && indexOf(next) >= 0;
    }

    @Nullable
    private PlayQueueItem availableAfter(final int index, final int count,
                                         final OfflineStore store) throws IOException {
        for (int offset = 1; offset <= count; offset++) {
            final PlayQueueItem item = getItem((index + offset) % size());
            if (store.find(item.getServiceId(), item.getUrl()) != null) {
                return item;
            }
        }
        return null;
    }

    // Current readers may finish; selection/loop boundaries must validate them again.
    public synchronized boolean pruneUnavailable(final OfflineStore store,
                                                  final boolean includeCurrent) throws IOException {
        final PlayQueueItem current = getItem();
        for (int i = size() - 1; i >= 0; i--) {
            final PlayQueueItem item = getItem(i);
            if ((includeCurrent || item != current)
                    && store.find(item.getServiceId(), item.getUrl()) == null) {
                remove(i);
            }
        }
        return current != null && indexOf(current) < 0;
    }
}
