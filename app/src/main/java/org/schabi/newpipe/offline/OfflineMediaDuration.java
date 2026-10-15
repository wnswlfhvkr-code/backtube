package org.schabi.newpipe.offline;

import android.media.MediaMetadataRetriever;

import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;

/** Optional duration from owned bytes; never resolves a source URI or opens the network. */
public final class OfflineMediaDuration {
    private OfflineMediaDuration() { }

    @WorkerThread
    public static long readSeconds(final File file) {
        if (!file.isFile() || !file.canRead()) {
            return -1;
        }
        MediaMetadataRetriever metadata = null;
        try {
            metadata = new MediaMetadataRetriever();
            metadata.setDataSource(file.getAbsolutePath());
            final String duration = metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                final long seconds = Long.parseLong(duration) / 1000;
                return seconds > 0 ? seconds : -1;
            }
        } catch (final RuntimeException ignored) {
            // A playable file may lack duration metadata or use an unsupported metadata codec.
        } finally {
            if (metadata != null) {
                try {
                    metadata.release();
                } catch (final IOException | RuntimeException ignored) {
                    // Metadata cleanup must not prevent saved playback.
                }
            }
        }
        return -1;
    }
}
