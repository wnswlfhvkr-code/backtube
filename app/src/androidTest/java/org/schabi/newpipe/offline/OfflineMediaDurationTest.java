package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Native codec coverage using generated test-package files; no saved user media is opened. */
@RunWith(AndroidJUnit4.class)
public class OfflineMediaDurationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getContext().getCacheDir());

    @Test public void generatedPcmWaveSuppliesDurationInSeconds() throws Exception {
        final File wave = temporary.newFile("generated-duration.wav");
        final int sampleRate = 8000;
        final int dataSize = sampleRate * 2 * 2;
        final ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataSize);
        header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        header.putShort((short) 1).putShort((short) 1).putInt(sampleRate);
        header.putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataSize);
        try (FileOutputStream output = new FileOutputStream(wave)) {
            output.write(header.array());
            output.write(new byte[dataSize]);
        }
        assertEquals(2, OfflineMediaDuration.readSeconds(wave));
    }

    @Test public void unavailableOrMalformedMetadataRemainsUnknown() throws Exception {
        assertEquals(-1, OfflineMediaDuration.readSeconds(
                new File(temporary.getRoot(), "missing-generated-file")));
        final File invalid = temporary.newFile("generated-invalid-media");
        try (FileOutputStream output = new FileOutputStream(invalid)) {
            output.write(new byte[]{1, 2, 3});
        }
        assertEquals(-1, OfflineMediaDuration.readSeconds(invalid));
    }
}
