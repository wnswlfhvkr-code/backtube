package org.schabi.newpipe.offline;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.Image;

import java.io.File;
import java.util.List;

/** Cheap artwork lookup only. Media is never decoded while constructing a queue. */
public final class OfflineThumbnailFiles {
    public static final int MAX_EDGE = 320;
    public static final long MAX_IMAGE_BYTES = 128 * 1024;

    private OfflineThumbnailFiles() { }

    public static boolean isVideo(final OfflineStore.Entry entry) {
        return entry.mime.startsWith("video/");
    }

    public static int playLabel(final OfflineStore.Entry entry) {
        return isVideo(entry) ? R.string.offline_video_play : R.string.offline_audio_play;
    }

    public static File directory(final OfflineStore store, final OfflineStore.Entry entry) {
        return new File(store.file(entry).getParentFile(), "thumbnails");
    }

    public static File file(final OfflineStore store, final OfflineStore.Entry entry) {
        final File media = store.file(entry);
        return new File(directory(store, entry), entry.id + "-" + entry.bytes + "-"
                + media.lastModified() + ".jpg");
    }

    public static List<Image> images(final OfflineStore store, final OfflineStore.Entry entry) {
        if (entry == null || entry.state != OfflineStore.State.READY) {
            return List.of();
        }
        final File media = store.file(entry);
        final File image = file(store, entry);
        if (!media.isFile() || media.length() != entry.bytes || !image.isFile()
                || image.length() == 0 || image.length() > MAX_IMAGE_BYTES) {
            return List.of();
        }
        return List.of(new Image(image.toURI().toString(), Image.HEIGHT_UNKNOWN,
                Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN));
    }

    static void remove(final OfflineStore store, final OfflineStore.Entry entry) {
        final File[] files = directory(store, entry).listFiles();
        if (files != null) {
            for (final File file : files) {
                if (file.getName().startsWith(entry.id + "-")
                        || file.getName().startsWith(entry.id + ".")) {
                    file.delete();
                }
            }
        }
    }
}
