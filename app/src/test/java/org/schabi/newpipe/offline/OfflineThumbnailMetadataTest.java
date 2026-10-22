package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.MediaMetadataRetriever;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.OfflinePlayQueue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;

/** Real saved identities and all queue factories; no remote artwork is permitted in a local tag. */
public class OfflineThumbnailMetadataTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private OfflineLibrary library;
    private OfflineStore.Entry entry;
    private MockedConstruction<MediaMetadataRetriever> codecs;

    @Before public void setUp() throws Exception {
        reset();
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.getRoot());
        library = OfflineLibrary.get(context);
        entry = library.store().create("Owned video", "https://example.invalid/video", 0,
                "file:///generated/video", "video/webm");
        library.store().copy(entry.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        entry = library.store().get(entry.id);
        codecs = mockConstruction(MediaMetadataRetriever.class);
    }

    @After public void tearDown() throws Exception {
        codecs.close();
        reset();
    }

    @Test public void persistedArtworkReachesSingleAllSelectedAndRestoredMetadata()
            throws Exception {
        final File media = library.store().file(entry);
        final File directory = new File(media.getParentFile(), "thumbnails");
        assertTrue(directory.mkdir());
        final File image = new File(directory, entry.id + "-" + entry.bytes + "-"
                + media.lastModified() + ".jpg");
        Files.write(image.toPath(), new byte[]{1, 2, 3});
        final String expected = image.toURI().toString();
        for (final PlayQueueItem item : List.of(library.queue(entry).getItem(),
                library.queueAll().getItem(), library.queueSelected(List.of(entry.id)).getItem())) {
            assertEquals(1, item.getThumbnails().size());
            assertEquals(expected, item.getThumbnails().get(0).getUrl());
            final OfflineMediaTag tag = new OfflineMediaTag(item, library.store());
            assertEquals(expected, tag.getThumbnailUrl());
            assertEquals(expected, tag.getMaybeStreamInfo().orElseThrow()
                    .getThumbnails().get(0).getUrl());
            assertEquals(expected, tag.withExtras("state").getThumbnailUrl());
        }
        assertEquals(expected, library.streamInfo(0, entry.localUrl()).getThumbnails()
                .get(0).getUrl());
    }

    @Test public void localTagNeverCarriesRemoteOrUnownedArtwork() throws Exception {
        for (final String url : List.of("https://example.invalid/art.jpg",
                "file:///other/private.jpg", "content://other/art")) {
            final StreamInfoItem metadata = new StreamInfoItem(0, entry.localUrl(), entry.title,
                    StreamType.VIDEO_STREAM);
            metadata.setThumbnails(List.of(new Image(url, -1, -1, Image.ResolutionLevel.UNKNOWN)));
            final OfflineMediaTag tag = new OfflineMediaTag(
                    new OfflinePlayQueue(List.of(metadata), 0).getItem(), library.store());
            assertEquals("", tag.getThumbnailUrl());
            assertTrue(tag.getMaybeStreamInfo().orElseThrow().getThumbnails().isEmpty());
        }
    }

    @Test public void changedMediaGenerationMissingAndOversizedImagesRemainPlaceholders()
            throws Exception {
        final File image = OfflineThumbnailFiles.file(library.store(), entry);
        assertTrue(image.getParentFile().mkdir());
        Files.write(image.toPath(), new byte[]{1, 2, 3});
        assertEquals(1, library.queue(entry).getItem().getThumbnails().size());
        assertTrue(library.store().file(entry).setLastModified(
                library.store().file(entry).lastModified() + 2000));
        assertTrue(library.queue(entry).getItem().getThumbnails().isEmpty());
        final File next = OfflineThumbnailFiles.file(library.store(), entry);
        Files.write(next.toPath(), new byte[(int) OfflineThumbnailFiles.MAX_IMAGE_BYTES + 1]);
        assertTrue(library.queue(entry).getItem().getThumbnails().isEmpty());
        assertTrue(library.store().file(entry).delete());
        assertTrue(library.queue(entry).getItem().getThumbnails().isEmpty());
    }

    @Test public void deletingSaveRemovesOnlyItsArtworkAndPreservesActiveMediaReader()
            throws Exception {
        final File image = OfflineThumbnailFiles.file(library.store(), entry);
        assertTrue(image.getParentFile().mkdir());
        Files.write(image.toPath(), new byte[]{1, 2, 3});
        final File media = library.store().retain(entry.id);
        library.store().delete(entry.id);
        assertTrue(media.isFile());
        org.junit.Assert.assertFalse(image.exists());
        library.store().release(entry.id);
        org.junit.Assert.assertFalse(media.exists());
    }

    private static void reset() throws Exception {
        final Field field = OfflineLibrary.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
