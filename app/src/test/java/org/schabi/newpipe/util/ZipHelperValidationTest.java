package org.schabi.newpipe.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import us.shandian.giga.io.FileStream;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipHelperValidationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File archive() throws IOException {
        final File file = temporary.newFile();
        final byte[] data = "generated test database bytes".getBytes(StandardCharsets.UTF_8);
        final CRC32 crc = new CRC32();
        crc.update(data);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            final ZipEntry entry = new ZipEntry("newpipe.db");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(data);
        }
        return file;
    }

    private StoredFileHelper source(final File file) throws IOException {
        final StoredFileHelper helper = mock(StoredFileHelper.class);
        when(helper.getStream()).thenAnswer(invocation -> new FileStream(file));
        return helper;
    }

    @Test public void readableArchiveIsAccepted() throws Exception {
        assertTrue(ZipHelper.isValidZipFile(source(archive())));
    }

    @Test public void ordinaryTextIsRejected() throws Exception {
        final File file = temporary.newFile();
        Files.writeString(file.toPath(), "generated non-archive input");
        assertFalse(ZipHelper.isValidZipFile(source(file)));
    }

    @Test public void corruptPayloadIsRejected() throws Exception {
        final File file = archive();
        final byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[30 + "newpipe.db".length()] ^= 1;
        Files.write(file.toPath(), bytes);
        assertFalse(ZipHelper.isValidZipFile(source(file)));
    }

    @Test public void readFailureIsRejected() throws Exception {
        final File file = archive();
        final StoredFileHelper helper = mock(StoredFileHelper.class);
        when(helper.getStream()).thenAnswer(invocation -> new FileStream(file) {
            @Override public int read(final byte[] b, final int off, final int count)
                    throws IOException {
                throw new IOException("generated read failure");
            }
        });
        assertFalse(ZipHelper.isValidZipFile(helper));
    }

    @Test public void canceledValidationIsRejected() throws Exception {
        final StoredFileHelper helper = source(archive());
        Thread.currentThread().interrupt();
        try {
            assertFalse(ZipHelper.isValidZipFile(helper));
        } finally {
            Thread.interrupted();
        }
    }
    @Test public void excessiveEntryCountIsRejected() throws Exception {
        final File file = temporary.newFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (int i = 0; i < 1025; i++) {
                out.putNextEntry(new ZipEntry("generated-" + i));
                out.closeEntry();
            }
        }
        assertFalse(ZipHelper.isValidZipFile(source(file)));
    }

    @Test public void excessiveInflatedSizeIsRejected() throws Exception {
        final File file = temporary.newFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            out.putNextEntry(new ZipEntry("generated-size-limit"));
            final byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 513; i++) {
                out.write(block);
            }
        }
        assertFalse(ZipHelper.isValidZipFile(source(file)));
    }

}
