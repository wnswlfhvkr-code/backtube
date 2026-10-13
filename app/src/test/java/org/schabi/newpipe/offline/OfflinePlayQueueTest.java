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
import static org.junit.Assert.assertNull;

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

    @Test public void transitionUsesIdentityAfterDeletingEarlierOpenReader() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry second = saved("second");
        saved("third");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        store.retain(first.id);
        store.delete(first.id);
        assertEquals(0, queue.reconcileTransition(second.localUrl(), store, true));
        assertEquals(second.localUrl(), queue.getItem().getUrl());
        store.release(first.id);
        // A later timeline removal callback still identifies B despite its old timeline index.
        assertEquals(0, queue.reconcileTransition(second.localUrl(), store, false));
        assertEquals(second.localUrl(), queue.getItem().getUrl());
    }

    @Test public void rejectedTimestampCallbackKeepsSavedSelection() throws Exception {
        final OfflineStore.Entry first = saved("first");
        saved("second");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        final StreamInfoItem remote = new StreamInfoItem(0, "https://example.test/remote",
                "remote", StreamType.AUDIO_STREAM);
        final PlayQueueItem item = new SinglePlayQueue(remote).getItem();
        assertFalse(queue.enqueueNextAndSelect(item));
        assertEquals(first.localUrl(), queue.getItem().getUrl());
        assertEquals(2, queue.size());
    }

    @Test public void multipleEarlierRemovalsPreserveTransitionTarget() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry second = saved("second");
        final OfflineStore.Entry target = saved("target");
        saved("last");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        store.delete(first.id);
        store.delete(second.id);
        assertEquals(0, queue.reconcileTransition(target.localUrl(), store, true));
        assertEquals(target.localUrl(), queue.getItem().getUrl());
    }

    @Test public void unavailableAutomaticTailStopsWithoutWrapping() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry last = saved("last");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        store.delete(last.id);
        assertEquals(-1, queue.reconcileTransition(last.localUrl(), store, true));
        assertEquals(first.localUrl(), queue.getItem().getUrl());
    }

    @Test public void unavailableAutomaticMiddleSelectsValidSuccessor() throws Exception {
        saved("first");
        final OfflineStore.Entry missing = saved("missing");
        final OfflineStore.Entry next = saved("next");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        store.delete(missing.id);
        assertEquals(1, queue.reconcileTransition(missing.localUrl(), store, true));
        assertEquals(next.localUrl(), queue.getItem().getUrl());
    }

    @Test public void unavailableAutomaticTailMayWrapOnlyWithRepeatAll() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry last = saved("last");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        store.delete(last.id);
        assertEquals(0, queue.reconcileTransition(last.localUrl(), store, true, true));
        assertEquals(first.localUrl(), queue.getItem().getUrl());
    }

    @Test public void failedTailStopsOffButRepeatAllCanSelectEarlierSave() throws Exception {
        final OfflineStore.Entry first = saved("first");
        saved("last");
        final OfflinePlayQueue off = new OfflinePlayQueue(store, "Saved copy");
        off.setIndex(1);
        assertFalse(off.discardFailedCurrent(store, false));
        assertEquals(1, off.size());
        final OfflinePlayQueue all = new OfflinePlayQueue(store, "Saved copy");
        all.setIndex(1);
        assertTrue(all.discardFailedCurrent(store, true));
        assertEquals(first.localUrl(), all.getItem().getUrl());
    }

    @Test public void failedMiddleAdvancesAndRepeatedFailuresExhaustSnapshot() throws Exception {
        saved("first");
        saved("middle");
        final OfflineStore.Entry next = saved("next");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        queue.setIndex(1);
        assertTrue(queue.discardFailedCurrent(store, false));
        assertEquals(next.localUrl(), queue.getItem().getUrl());
        assertTrue(queue.discardFailedCurrent(store, true));
        assertFalse(queue.discardFailedCurrent(store, true));
        assertTrue(queue.isEmpty());
    }

    @Test public void deletedLastCompletionStopsUnlessRepeatAll() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry last = saved("last");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        queue.setIndex(1);
        store.retain(last.id);
        store.delete(last.id);
        assertNull(queue.completionSuccessor(store, false));
        assertEquals(first.localUrl(), queue.completionSuccessor(store, true).getUrl());
        store.release(last.id);
    }

    @Test public void completionSkipsUnavailableSuccessorsAndBoundsRepeatSearch() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry missing = saved("missing");
        final OfflineStore.Entry next = saved("next");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        store.delete(missing.id);
        assertEquals(next.localUrl(), queue.completionSuccessor(store, false).getUrl());
        queue.setIndex(2);
        assertNull(queue.completionSuccessor(store, false));
        store.delete(first.id);
        store.delete(next.id);
        assertNull(queue.completionSuccessor(store, true));
    }

    @Test public void prunedAutomaticIdentityCannotResolveToAnotherCurrentFile() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflineStore.Entry pruned = saved("pruned");
        final OfflineStore.Entry next = saved("next");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        store.delete(pruned.id);
        assertFalse(queue.pruneUnavailable(store, false));
        assertEquals(2, queue.size());

        // A late timeline callback still names the file removed from this snapshot.
        assertNull(queue.transitionTarget(pruned.localUrl(), store, false));
        assertEquals(-1, queue.reconcileTransition(pruned.localUrl(), store, true, false));
        assertEquals(first.localUrl(), queue.getItem().getUrl());
        assertEquals(next.localUrl(), queue.getItem(1).getUrl());
    }

    @Test public void newSaveOutsideSnapshotCannotBecomeAnAutomaticTransition() throws Exception {
        final OfflineStore.Entry first = saved("first");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");
        final OfflineStore.Entry later = saved("saved after playback started");

        assertNull(queue.transitionTarget(later.localUrl(), store, true));
        assertEquals(-1, queue.reconcileTransition(later.localUrl(), store, true, true));
        assertEquals(1, queue.size());
        assertEquals(first.localUrl(), queue.getItem().getUrl());
        assertEquals(2, new OfflinePlayQueue(store, "Saved copy").size());
    }

    @Test public void absentTransitionMetadataMayUseTheKnownCurrentIdentity() throws Exception {
        final OfflineStore.Entry first = saved("first");
        saved("next");
        final OfflinePlayQueue queue = new OfflinePlayQueue(store, "Saved copy");

        assertEquals(first.localUrl(), queue.transitionTarget(null, store, false).getUrl());
        assertEquals(0, queue.reconcileTransition(null, store, true, false));
        assertEquals(first.localUrl(), queue.getItem().getUrl());
    }
}
