package org.schabi.newpipe.player.seekbarpreview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import android.graphics.Bitmap;

import androidx.collection.SparseArrayCompat;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** The generation decision and insertion must be indivisible under the data monitor. */
public class SeekbarPreviewPublicationTest {
    @Test
    public void newRequestBetweenOldCheckAndPublishCannotReceiveOldFrames() throws Exception {
        final SeekbarPreviewThumbnailHolder holder = new SeekbarPreviewThumbnailHolder();
        final SparseArrayCompat<Supplier<Bitmap>> data = data(holder);
        final SparseArrayCompat<Supplier<Bitmap>> generated = new SparseArrayCompat<>();
        generated.put(1000, () -> null);
        final UUID old = UUID.randomUUID();
        setRequest(holder, old);
        final Method publish = SeekbarPreviewThumbnailHolder.class.getDeclaredMethod(
                "publishGeneratedData", UUID.class, SparseArrayCompat.class);
        publish.setAccessible(true);
        final AtomicReference<Boolean> accepted = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Thread worker = new Thread(() -> {
            try {
                accepted.set((Boolean) publish.invoke(holder, old, generated));
            } catch (final Throwable failure) {
                error.set(failure);
            }
        }, "preview-publish-test");

        synchronized (data) {
            worker.start();
            final long deadline = System.nanoTime() + 3_000_000_000L;
            while (worker.getState() != Thread.State.BLOCKED && worker.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(Thread.State.BLOCKED, worker.getState());
            setRequest(holder, UUID.randomUUID());
        }
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertNull(error.get());
        assertEquals(Boolean.FALSE, accepted.get());
        assertEquals(0, data.size());
    }

    @SuppressWarnings("unchecked")
    private static SparseArrayCompat<Supplier<Bitmap>> data(
            final SeekbarPreviewThumbnailHolder holder) throws Exception {
        final Field field = SeekbarPreviewThumbnailHolder.class
                .getDeclaredField("seekbarPreviewData");
        field.setAccessible(true);
        return (SparseArrayCompat<Supplier<Bitmap>>) field.get(holder);
    }

    private static void setRequest(final SeekbarPreviewThumbnailHolder holder,
                                   final UUID id) throws Exception {
        final Field field = SeekbarPreviewThumbnailHolder.class
                .getDeclaredField("currentUpdateRequestIdentifier");
        field.setAccessible(true);
        field.set(holder, id);
    }
}
