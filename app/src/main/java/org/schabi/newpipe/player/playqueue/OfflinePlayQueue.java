package org.schabi.newpipe.player.playqueue;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.offline.OfflineStore;

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
