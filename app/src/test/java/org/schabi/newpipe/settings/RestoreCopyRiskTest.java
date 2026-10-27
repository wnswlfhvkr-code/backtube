package org.schabi.newpipe.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.settings.export.BackupFileLocator;
import org.schabi.newpipe.settings.export.ImportExportManager;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import us.shandian.giga.io.FileStream;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Diagnostic of unresolved R1, not an assertion that replacement is safe. */
public class RestoreCopyRiskTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void readFailureDuringReplacementCanDamageExistingDatabase() throws Exception {
        final File existing = temporary.newFile();
        final byte[] original = new byte[64 * 1024];
        Arrays.fill(original, (byte) 42);
        Files.write(existing.toPath(), original);
        final File archive = temporary.newFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            out.putNextEntry(new ZipEntry("newpipe.db"));
            final byte[] replacement = new byte[128 * 1024];
            new java.util.Random(1).nextBytes(replacement);
            out.write(replacement);
        }
        final StoredFileHelper helper = mock(StoredFileHelper.class);
        final java.util.concurrent.atomic.AtomicInteger opens =
                new java.util.concurrent.atomic.AtomicInteger();
        when(helper.getStream()).thenAnswer(invocation -> {
            if (opens.getAndIncrement() == 0) {
                return new FileStream(archive);
            }
            return new FileStream(archive) {
                private int consumed;
                @Override public int read(final byte[] b, final int off, final int count)
                        throws IOException {
                    if (consumed > 8192) {
                        throw new IOException("generated mid-copy failure");
                    }
                    final int result = super.read(b, off, count);
                    consumed += Math.max(result, 0);
                    return result;
                }
            };
        });
        assertTrue(org.schabi.newpipe.util.ZipHelper.isValidZipFile(helper));
        final BackupFileLocator locator = mock(BackupFileLocator.class);
        when(locator.getDb()).thenReturn(existing.toPath());
        final ImportExportManager manager = new ImportExportManager(locator);
        assertThrows(IOException.class, () -> manager.extractDb(helper));
        assertFalse("R1 diagnostic did not reproduce partial replacement",
                Arrays.equals(original, Files.readAllBytes(existing.toPath())));
    }
}
