package org.schabi.newpipe.player.helper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Local silence for both the target service and the separate test APK's media player. */
final class AudioFocusWav {
    private AudioFocusWav() {
    }

    static File create(final File directory) throws IOException {
        final int rate = 8000;
        final int length = rate * 30 * 2;
        final ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length);
        final File file = File.createTempFile("focus-handoff-", ".wav", directory);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(header.array());
            output.write(new byte[length]);
        }
        return file;
    }
}
