package org.schabi.newpipe.offline;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

public class OfflineStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicLong now = new AtomicLong(1000);
    private File root;
    private OfflineStore store;

    @Before public void setup() throws IOException {
        root = temporary.newFolder();
        store = new OfflineStore(root, 100, 1000, now::get);
    }

    private OfflineStore.Entry create() throws IOException {
        return store.create("My recording", "https://example.invalid/owned", 0,
                "content://test/recording", "audio/wav");
    }

    @Test public void completeCopySurvivesReopenAndPreservesOriginalIdentity() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        store = new OfflineStore(root, 100, 1000, now::get);
        assertEquals(OfflineStore.State.READY, store.list().get(0).state);
        assertEquals(32, store.usedBytes());
        assertEquals(entry.id, store.find(0, entry.origin).id);
        assertEquals(32, store.file(store.find(0, entry.origin)).length());
    }

    @Test public void unknownLengthCannotExceedQuotaAndFailureIsNotPlayable() throws Exception {
        final OfflineStore.Entry entry = create();
        assertThrows(IOException.class,
                () -> store.copy(entry.id, new ByteArrayInputStream(new byte[101])));
        assertEquals(OfflineStore.State.FAILED, store.list().get(0).state);
        assertNull(store.find(0, entry.origin));
        assertEquals(0, store.usedBytes());
    }

    @Test public void expiryDeletesOnlyManagedCopy() throws Exception {
        final File original = temporary.newFile();
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        now.addAndGet(1001);
        assertNull(store.find(0, entry.origin));
        assertEquals(OfflineStore.State.EXPIRED, store.list().get(0).state);
        assertFalse(store.file(entry).exists());
        assertTrue(original.exists());
    }

    @Test public void interruptedImportIsRecoverableButNeverComplete() throws Exception {
        final OfflineStore.Entry entry = create();
        store = new OfflineStore(root, 100, 1000, now::get);
        assertEquals(OfflineStore.State.INTERRUPTED, store.list().get(0).state);
        assertNull(store.find(0, entry.origin));
        store.prepareRetry(entry.id);
        store.copy(entry.id, new ByteArrayInputStream(new byte[20]));
        assertNotNull(store.find(0, entry.origin));
    }

    @Test public void deleteDuringImportCannotResurrectItem() throws Exception {
        final OfflineStore.Entry entry = create();
        final InputStream input = new ByteArrayInputStream(new byte[32]) {
            @Override public synchronized int read(final byte[] bytes, final int off,
                                                   final int length) {
                try {
                    store.delete(entry.id);
                } catch (final IOException error) {
                    throw new AssertionError(error);
                }
                return super.read(bytes, off, length);
            }
        };
        assertThrows(IOException.class, () -> store.copy(entry.id, input));
        assertTrue(store.list().isEmpty());
        assertFalse(store.file(entry).exists());
    }

    @Test public void missingOrTruncatedFileIsNotReady() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        assertTrue(store.file(entry).delete());
        assertNull(store.find(0, entry.origin));
        assertEquals(OfflineStore.State.FAILED, store.list().get(0).state);
    }

    @Test public void emptyInputIsNotPlayable() throws Exception {
        final OfflineStore.Entry entry = create();
        assertThrows(IOException.class,
                () -> store.copy(entry.id, new ByteArrayInputStream(new byte[0])));
        assertNull(store.find(0, entry.origin));
    }

    @Test public void gigaPartialSurvivesRestartButIsNotPlayableUntilFinished() throws Exception {
        final OfflineStore.Entry entry = store.beginDownload("Owned audio", "owned:test", 0,
                "audio/wav");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(store.file(entry))) {
            out.write(new byte[20]);
        }
        store = new OfflineStore(root, 100, 1000, now::get);
        assertEquals(20, store.file(entry).length());
        assertNull(store.find(0, entry.origin));
        store.finishDownload(entry.id);
        assertNotNull(store.find(0, entry.origin));
    }

    @Test public void gigaCannotFinalizeOverQuotaOrDeletedEntry() throws Exception {
        final OfflineStore.Entry entry = store.beginDownload("Owned audio", "owned:test", 0,
                "audio/wav");
        assertThrows(IOException.class, () -> store.checkGrowth(entry.id, 101));
        store.delete(entry.id);
        assertThrows(IOException.class, () -> store.finishDownload(entry.id));
    }

    @Test public void currentReaderSurvivesExpiryAndReleasesSpaceWhenClosed() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        store.retain(entry.id);
        now.addAndGet(1001);
        assertNull(store.find(0, entry.origin));
        assertTrue(store.file(entry).exists());
        store.release(entry.id);
        assertFalse(store.file(entry).exists());
    }

    @Test public void expiredReaderIsCleanedAfterProcessRestart() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        store.retain(entry.id);
        now.addAndGet(1001);
        store.list();
        store = new OfflineStore(root, 100, 1000, now::get);
        assertEquals(0, store.usedBytes());
    }

    @Test public void pauseWhileSourceOpensCannotBeUndoneByCopy() throws Exception {
        final OfflineStore.Entry entry = create();
        store.interrupt(entry.id);
        assertThrows(IOException.class,
                () -> store.copy(entry.id, new ByteArrayInputStream(new byte[32])));
        assertEquals(OfflineStore.State.INTERRUPTED, store.get(entry.id).state);
    }
}
