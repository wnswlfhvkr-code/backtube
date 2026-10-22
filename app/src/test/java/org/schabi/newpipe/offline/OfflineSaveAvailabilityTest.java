package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

/** Completion requires readable owned bytes, not only a READY record and matching length. */
public class OfflineSaveAvailabilityTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void completeIdentityPreservesAliasesKindServiceAndOwnedLocalUrl()
            throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        final String origin = "https://www.youtube.com/watch?v=abcdefghijk";
        final OfflineStore.Entry entry = store.create("Generated", origin, 0,
                "file:///generated/owned", "video/webm");
        store.copy(entry.id, new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertTrue(store.hasCompletedSave(0, "https://youtu.be/abcdefghijk?t=45", "video/mp4"));
        assertTrue(store.hasCompletedSave(0, entry.localUrl(), "video/webm"));
        assertFalse(store.hasCompletedSave(0, origin, "audio/mp4"));
        assertFalse(store.hasCompletedSave(1, origin, "video/mp4"));
        assertFalse(store.hasCompletedSave(0, origin.replace("abcdefghijk", "lmnopqrstuv"),
                "video/mp4"));
        assertEquals(1, store.list().size());
    }

    @Test public void pendingFailedInterruptedMissingAndTruncatedAreNeverComplete()
            throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        final String origin = "https://www.youtube.com/watch?v=abcdefghijk";
        final OfflineStore.Entry entry = store.beginDownload("Generated", origin, 0, "video/webm");
        assertFalse(store.hasCompletedSave(0, origin, "video/"));
        store.fail(entry.id);
        assertFalse(store.hasCompletedSave(0, origin, "video/"));
        store.interrupt(entry.id);
        assertFalse(store.hasCompletedSave(0, origin, "video/"));
        java.nio.file.Files.write(store.file(entry).toPath(), new byte[]{1, 2, 3});
        store.beginDownload("Retry", origin, 0, "video/webm");
        store.finishDownload(entry.id);
        assertTrue(store.hasCompletedSave(0, origin, "video/"));
        java.nio.file.Files.write(store.file(entry).toPath(), new byte[]{1});
        assertFalse(store.hasCompletedSave(0, origin, "video/"));
        assertTrue(store.file(entry).delete());
        assertFalse(store.hasCompletedSave(0, origin, "video/"));
        assertEquals(1, store.list().size());
    }

    @Test public void unreadableReadyCopyIsNotTreatedAsCompleteForAnotherRequest()
            throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        final String origin = "https://www.youtube.com/watch?v=abcdefghijk";
        final OfflineStore.Entry entry = store.beginDownload("Generated", origin, 0, "video/webm");
        final File media = store.file(entry);
        try (FileOutputStream output = new FileOutputStream(media)) {
            output.write(new byte[]{1, 2, 3});
        }
        store.finishDownload(entry.id);
        assertTrue(media.setReadable(false, false));
        try {
            assertFalse(media.canRead());
            assertEquals(OfflineStore.State.DOWNLOADING,
                    store.beginDownload("Retry", origin, 0, "video/webm").state);
        } finally {
            assertTrue(media.setReadable(true, true));
        }
    }
}
