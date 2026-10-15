package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

/** Verifies Android unlink while the actual app-owned output stream remains open. */
@RunWith(AndroidJUnit4.class)
public class OfflineFileStreamDeviceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    @Test public void deletedDownloadCannotWriteMoreBytes() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 100);
        final OfflineStore.Entry entry = store.beginDownload("One", "", 0, "audio/wav");
        final File file = store.file(entry);
        try (OfflineFileStream output = new OfflineFileStream(file, store, entry.id)) {
            output.write(new byte[20]);
            store.delete(entry.id);
            assertFalse("Android unlinks the actual open store file", file.exists());
            assertThrows(IOException.class, () -> output.write(new byte[10]));
            assertThrows(IOException.class, () -> output.setLength(30));
        }
        assertEquals(0, store.usedBytes());
    }
}
