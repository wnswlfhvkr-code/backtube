package org.schabi.newpipe.offline;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

public class OfflineStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File root;
    private OfflineStore store;

    @Before public void setup() throws IOException {
        root = temporary.newFolder();
        store = new OfflineStore(root, 100);
    }

    private OfflineStore.Entry create() throws IOException {
        return store.create("My recording", "https://example.invalid/owned", 0,
                "content://test/recording", "audio/wav");
    }

    @Test public void completeCopySurvivesReopenAndPreservesOriginalIdentity() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        store = new OfflineStore(root, 100);
        assertEquals(OfflineStore.State.READY, store.list().get(0).state);
        assertEquals(0, store.get(entry.id).expiresAt);
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

    @Test public void savedCopySurvivesMoreThanEightDaysAndReopen() throws Exception {
        final File original = temporary.newFile();
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        writeLegacyMetadata(entry, OfflineStore.State.READY,
                System.currentTimeMillis() - 9L * 24 * 60 * 60 * 1000);
        assertNotNull(store.find(0, entry.origin));
        store = new OfflineStore(root, 100);
        assertEquals(OfflineStore.State.READY, store.get(entry.id).state);
        assertEquals(0, store.get(entry.id).expiresAt);
        assertTrue(store.file(entry).exists());
        assertTrue(original.exists());
    }

    @Test public void interruptedImportIsRecoverableButNeverComplete() throws Exception {
        final OfflineStore.Entry entry = create();
        store = new OfflineStore(root, 100);
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
        store = new OfflineStore(root, 100);
        assertEquals(20, store.file(entry).length());
        assertNull(store.find(0, entry.origin));
        store.finishDownload(entry.id);
        assertNotNull(store.find(0, entry.origin));
        assertEquals(0, store.get(entry.id).expiresAt);
    }

    @Test public void gigaCannotFinalizeOverQuotaOrDeletedEntry() throws Exception {
        final OfflineStore.Entry entry = store.beginDownload("Owned audio", "owned:test", 0,
                "audio/wav");
        assertThrows(IOException.class, () -> store.checkGrowth(entry.id, 101));
        store.delete(entry.id);
        assertThrows(IOException.class, () -> store.finishDownload(entry.id));
    }

    @Test public void explicitDeleteWaitsForCurrentReaderBeforeReclaimingSpace() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        store.retain(entry.id);
        store.delete(entry.id);
        assertNull(store.find(0, entry.origin));
        assertTrue(store.file(entry).exists());
        store.release(entry.id);
        assertFalse(store.file(entry).exists());
    }

    @Test public void legacyExpiredIntactFileIsRestoredInsteadOfDeleted() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        writeLegacyMetadata(entry, OfflineStore.State.EXPIRED, 1);
        store = new OfflineStore(root, 100);
        assertEquals(OfflineStore.State.READY, store.get(entry.id).state);
        assertEquals(32, store.usedBytes());
        assertEquals(0, store.get(entry.id).expiresAt);
        assertNotNull(store.find(0, entry.localUrl()));
    }

    @Test public void legacyReadyDeadlineIsClearedDurablyBeforeCleanup() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        writeLegacyMetadata(entry, OfflineStore.State.READY, 1);
        writeLegacyMetadata(entry, OfflineStore.State.READY,
                System.currentTimeMillis() - 9L * 24 * 60 * 60 * 1000);
        store = new OfflineStore(root, 100);
        assertNotNull(store.find(0, entry.origin));
        final Properties properties = new Properties();
        try (InputStream input = new FileInputStream(new File(root, entry.id + ".properties"))) {
            properties.load(input);
        }
        assertEquals("0", properties.getProperty("expires"));
        store = new OfflineStore(root, 100);
        assertEquals(32, store.retain(entry.id).length());
        store.release(entry.id);
        assertTrue(store.file(entry).exists());
    }

    @Test public void legacyExpiredMissingFileRemainsUnavailable() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        writeLegacyMetadata(entry, OfflineStore.State.EXPIRED, 1);
        assertTrue(store.file(entry).delete());
        store = new OfflineStore(root, 100);
        assertEquals(OfflineStore.State.FAILED, store.get(entry.id).state);
        assertNull(store.find(0, entry.localUrl()));
        assertThrows(IOException.class, () -> store.retain(entry.id));
    }

    @Test public void legacyExpiredTruncatedFileIsNotPlayedOrSilentlyDeleted() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[32]));
        writeLegacyMetadata(entry, OfflineStore.State.EXPIRED, 1);
        try (FileOutputStream output = new FileOutputStream(store.file(entry))) {
            output.write(new byte[8]);
        }
        store = new OfflineStore(root, 100);
        assertEquals(OfflineStore.State.FAILED, store.get(entry.id).state);
        assertNull(store.find(0, entry.localUrl()));
        assertEquals(8, store.usedBytes());
        store.delete(entry.id);
        assertEquals(0, store.usedBytes());
    }

    @Test public void fullLibraryRejectsNewSavesWithoutEvictingExistingItems() throws Exception {
        final OfflineStore.Entry entry = create();
        store.copy(entry.id, new ByteArrayInputStream(new byte[100]));
        writeLegacyMetadata(entry, OfflineStore.State.READY,
                System.currentTimeMillis() - 9L * 24 * 60 * 60 * 1000);
        store = new OfflineStore(root, 100);
        assertThrows(OfflineStore.StorageFullException.class, this::create);
        assertThrows(OfflineStore.StorageFullException.class,
                () -> store.beginDownload("New", "", 0, "audio/wav"));
        assertEquals(1, store.list().size());
        assertEquals(100, store.usedBytes());
        assertNotNull(store.find(0, entry.origin));
    }

    @Test
    public void failedNewCopyPreservesSavedBytesAndReclaimsOnlyItsOwnPartial() throws Exception {
        final OfflineStore.Entry saved = create();
        store.copy(saved.id, new ByteArrayInputStream(new byte[80]));
        final OfflineStore.Entry extra = create();
        assertThrows(IOException.class,
                () -> store.copy(extra.id, new ByteArrayInputStream(new byte[21])));
        assertEquals(OfflineStore.State.READY, store.get(saved.id).state);
        assertEquals(OfflineStore.State.FAILED, store.get(extra.id).state);
        assertEquals(80, store.usedBytes());
        assertEquals(80, store.file(saved).length());
    }

    @Test public void preallocatedDownloadBytesStillCountAgainstCapacityAfterReopen()
            throws Exception {
        final OfflineStore.Entry saved = create();
        store.copy(saved.id, new ByteArrayInputStream(new byte[80]));
        final OfflineStore.Entry pending = store.beginDownload("Pending", "", 0, "audio/wav");
        try (java.io.RandomAccessFile output =
                     new java.io.RandomAccessFile(store.file(pending), "rw")) {
            output.setLength(20);
        }
        store = new OfflineStore(root, 100);
        assertEquals(100, store.usedBytes());
        assertEquals(OfflineStore.State.DOWNLOADING, store.get(pending.id).state);
        assertNull(store.find(0, pending.localUrl()));
        assertThrows(OfflineStore.StorageFullException.class, this::create);
        assertThrows(OfflineStore.StorageFullException.class,
                () -> store.checkGrowth(pending.id, 21));
        store.checkGrowth(pending.id, 20); // Overwriting reserved bytes needs no extra space.
        store.delete(pending.id);
        assertEquals(80, store.usedBytes());
        assertNotNull(store.find(0, saved.localUrl()));
    }

    @Test public void closingReaderDoesNotSilentlyRemoveDamagedSavedFile() throws Exception {
        final OfflineStore.Entry saved = create();
        store.copy(saved.id, new ByteArrayInputStream(new byte[32]));
        store.retain(saved.id);
        try (FileOutputStream output = new FileOutputStream(store.file(saved))) {
            output.write(new byte[8]);
        }
        assertEquals(OfflineStore.State.FAILED, store.get(saved.id).state);
        store.release(saved.id);
        assertEquals(8, store.usedBytes());
        assertTrue(store.file(saved).isFile());
    }

    private void writeLegacyMetadata(final OfflineStore.Entry entry, final OfflineStore.State state,
                                     final long expiry) throws IOException {
        final File metadata = new File(root, entry.id + ".properties");
        final Properties properties = new Properties();
        try (InputStream input = new FileInputStream(metadata)) {
            properties.load(input);
        }
        properties.setProperty("state", state.name());
        properties.setProperty("expires", Long.toString(expiry));
        try (FileOutputStream output = new FileOutputStream(metadata)) {
            properties.store(output, "Legacy retention fixture");
        }
    }

    @Test public void pauseWhileSourceOpensCannotBeUndoneByCopy() throws Exception {
        final OfflineStore.Entry entry = create();
        store.interrupt(entry.id);
        assertThrows(IOException.class,
                () -> store.copy(entry.id, new ByteArrayInputStream(new byte[32])));
        assertEquals(OfflineStore.State.INTERRUPTED, store.get(entry.id).state);
    }
}
