package org.schabi.newpipe.offline;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.OfflinePlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class OfflinePlayQueueTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private OfflineStore store;

    @Before public void setup() throws Exception {
        store = new OfflineStore(temporary.newFolder(), 1000);
    }

    private OfflineStore.Entry saved(final String title) throws Exception {
        final OfflineStore.Entry entry = store.create(title, "https://example.invalid/" + title,
                0, "content://generated/" + title, "audio/wav");
        store.copy(entry.id, new ByteArrayInputStream(new byte[20]));
        return entry;
    }

    @Test public void queueUsesReadyLocalFilesInShelfOrder() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry second = saved("second");
        store.create("unfinished", "", 0, "content://generated/unfinished", "audio/wav");
        store.move(second.id, -1);
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        assertEquals(2, queue.size());
        assertEquals(second.localUrl(), queue.getItem(0).getUrl());
        assertEquals(first.localUrl(), queue.getItem(1).getUrl());
        assertTrue(queue.isComplete());
        assertTrue(queue.isOfflineOnly());
        queue.fetch();
        assertEquals(2, queue.size());
    }

    @Test public void shelfChangesDoNotChangeRunningSnapshot() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry second = saved("second");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        store.move(second.id, -1);
        saved("new");
        assertEquals(2, queue.size());
        assertEquals(first.localUrl(), queue.getItem(0).getUrl());
        assertEquals(3, new OfflinePlayQueue(store, "Saved copy").size());
    }

    @Test public void missingAndTruncatedFilesAreExcluded() throws Exception {
        final OfflineStore.Entry missing = saved("missing");
        final OfflineStore.Entry truncated = saved("truncated");
        assertTrue(store.file(missing).delete());
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(store.file(truncated))) {
            out.write(1);
        }
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        assertTrue(queue.isEmpty());
        assertTrue(queue.isOfflineOnly());
    }

    @Test public void onlineAppendAndEnqueueCannotAlterSavedQueue() throws Exception {
        saved("first");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        final PlayQueueItem online = new SinglePlayQueue(new StreamInfoItem(0,
                "https://example.invalid/online", "online", StreamType.AUDIO_STREAM)).getItem();
        queue.append(List.of(online));
        queue.enqueueNext(online, false);
        assertEquals(1, queue.size());
        assertEquals(0, queue.getIndex());
        queue.remove(0);
        queue.append(List.of(online));
        assertTrue(queue.isEmpty());
        assertTrue(queue.isOfflineOnly());
    }

    @Test public void onlineConstructorAndMixedAppendAreRejected() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final StreamInfoItem online = new StreamInfoItem(0, "https://example.invalid/online",
                "online", StreamType.AUDIO_STREAM);
        assertThrows(IllegalArgumentException.class,
                () -> new OfflinePlayQueue(List.of(online), 0));
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        queue.append(List.of(queue.getItem(), new SinglePlayQueue(online).getItem()));
        assertEquals(1, queue.size());
        assertEquals(first.localUrl(), queue.getItem().getUrl());
    }

    @Test public void deletedFutureItemIsSkippedWhileCurrentReaderMayFinish() throws Exception {
        final OfflineStore.Entry current = saved("current");
        final OfflineStore.Entry deleted = saved("deleted");
        final OfflineStore.Entry next = saved("next");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        store.retain(current.id);
        store.delete(current.id);
        store.delete(deleted.id);
        assertFalse(queue.pruneUnavailable(store, false));
        assertEquals(2, queue.size());
        assertTrue(store.file(current).isFile());
        assertTrue(queue.pruneUnavailable(store, true));
        assertEquals(next.localUrl(), queue.getItem().getUrl());
        store.release(current.id);
        assertFalse(store.file(current).exists());
    }

    @Test public void pruningPreservesSelectionAndFiniteNextPreviousRepeat() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry deleted = saved("deleted");
        final OfflineStore.Entry last = saved("last");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        queue.setIndex(2);
        store.delete(deleted.id);
        assertFalse(queue.pruneUnavailable(store, true));
        assertEquals(last.localUrl(), queue.getItem().getUrl());
        queue.offsetIndex(-1);
        assertEquals(first.localUrl(), queue.getItem().getUrl());
        queue.offsetIndex(1);
        assertEquals(last.localUrl(), queue.getItem().getUrl());
        queue.offsetIndex(1);
        assertEquals(first.localUrl(), queue.getItem().getUrl());
    }

    @Test public void allDeletedStopsWithoutResurrectionOrLoop() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry second = saved("second");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        store.delete(first.id);
        store.delete(second.id);
        assertTrue(queue.pruneUnavailable(store, true));
        assertTrue(queue.isEmpty());
        assertFalse(queue.pruneUnavailable(store, true));
        queue.offsetIndex(1);
        assertTrue(queue.isEmpty());
    }

    @Test public void intentSerializationPreservesOfflineScopeAndOrder() throws Exception {
        saved("first");
        final OfflineStore.Entry second = saved("second");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        queue.setIndex(1);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(queue);
        }
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            final PlayQueue restored = (PlayQueue) in.readObject();
            assertTrue(restored.isOfflineOnly());
            assertEquals(second.localUrl(), restored.getItem().getUrl());
        }
    }
}
