package org.schabi.newpipe.offline;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class OfflineFileStreamTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void randomAccessOutputCannotGrowPastSharedQuota() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 100);
        final OfflineStore.Entry first = store.beginDownload("One", "", 0, "audio/wav");
        final OfflineStore.Entry second = store.beginDownload("Two", "", 0, "audio/wav");
        try (OfflineFileStream one = new OfflineFileStream(store.file(first), store, first.id);
             OfflineFileStream two = new OfflineFileStream(store.file(second), store, second.id)) {
            one.write(new byte[60]);
            assertThrows(IOException.class, () -> two.setLength(41));
            two.write(new byte[40]);
            one.seek(59);
            one.write((byte) 1);
            assertThrows(IOException.class, () -> one.write((byte) 2));
            assertEquals(100, store.usedBytes());
        }
    }

    @Test public void deletedIdentityCannotWriteThroughStillOpenHandle() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 100);
        final OfflineStore.Entry entry = store.beginDownload("One", "", 0, "audio/wav");
        // Windows cannot unlink an open RandomAccessFile. Keep a live backing handle here;
        // OfflineFileStreamDeviceTest verifies Android unlink of the actual store file.
        final File backing = temporary.newFile("live-output");
        try (OfflineFileStream output = new OfflineFileStream(backing, store, entry.id)) {
            output.write(new byte[20]);
            store.delete(entry.id);
            assertThrows(IOException.class, () -> output.write(new byte[10]));
            assertThrows(IOException.class, () -> output.setLength(30));
            assertEquals(20, backing.length());
        }
        assertEquals(0, store.usedBytes());
    }
}
