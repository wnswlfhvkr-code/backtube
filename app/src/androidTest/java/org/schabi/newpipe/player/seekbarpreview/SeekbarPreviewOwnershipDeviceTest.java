package org.schabi.newpipe.player.seekbarpreview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import androidx.collection.SparseArrayCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.stream.Frameset;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Exercises real Android bitmap cropping and holder generation ownership. */
@RunWith(AndroidJUnit4.class)
public class SeekbarPreviewOwnershipDeviceTest {
    @Test
    public void staleResetCannotClearCurrentPreview() throws Exception {
        final SeekbarPreviewThumbnailHolder holder = new SeekbarPreviewThumbnailHolder();
        final SparseArrayCompat<Supplier<Bitmap>> data = data(holder);
        data.put(1000, () -> null);
        final UUID stale = UUID.randomUUID();
        setRequest(holder, UUID.randomUUID());

        final Method reset = method("resetFromAsync", int.class, List.class, UUID.class);
        reset.invoke(holder, SeekbarPreviewThumbnailHelper.SeekbarPreviewThumbnailType.NONE,
                List.of(), stale);
        assertEquals("old reset cleared the current item's preview", 1, data.size());
    }

    @Test
    public void staleWorkerStopsBeforeLoadingWhenGenerationAlreadyChanged() throws Exception {
        final SeekbarPreviewThumbnailHolder holder = new SeekbarPreviewThumbnailHolder();
        final SparseArrayCompat<Supplier<Bitmap>> data = data(holder);
        final UUID old = UUID.randomUUID();
        setRequest(holder, old);
        final Method generate = method("generateDataFrom", Frameset.class, UUID.class);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Thread worker = new Thread(() -> {
            try {
                generate.invoke(holder, oneMissingPage(), old);
            } catch (final Throwable failure) {
                error.set(failure);
            }
        }, "preview-test-old-worker");

        synchronized (data) {
            worker.start();
            final long deadline = android.os.SystemClock.uptimeMillis() + 3000;
            while (worker.getState() != Thread.State.BLOCKED
                    && worker.isAlive() && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("old worker never reached the shared preview monitor",
                    Thread.State.BLOCKED, worker.getState());
            setRequest(holder, UUID.randomUUID());
        }
        worker.join(3000);
        assertFalse("preview worker did not finish", worker.isAlive());
        assertNull(error.get());
        assertEquals("old worker published into a newer item's preview", 0, data.size());
    }

    @Test
    public void publicationChecksOwnershipWhileHoldingThePreviewMonitor() throws Exception {
        final SeekbarPreviewThumbnailHolder holder = new SeekbarPreviewThumbnailHolder();
        final SparseArrayCompat<Supplier<Bitmap>> data = data(holder);
        final SparseArrayCompat<Supplier<Bitmap>> generated = new SparseArrayCompat<>();
        generated.put(1000, () -> null);
        final UUID old = UUID.randomUUID();
        setRequest(holder, old);
        final Method publish = method("publishGeneratedData", UUID.class,
                SparseArrayCompat.class);
        final AtomicReference<Boolean> accepted = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Thread worker = new Thread(() -> {
            try {
                accepted.set((Boolean) publish.invoke(holder, old, generated));
            } catch (final Throwable failure) {
                error.set(failure);
            }
        }, "preview-test-publish");

        synchronized (data) {
            worker.start();
            final long deadline = android.os.SystemClock.uptimeMillis() + 3000;
            while (worker.getState() != Thread.State.BLOCKED
                    && worker.isAlive() && android.os.SystemClock.uptimeMillis() < deadline) {
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

    @Test
    public void currentWorkerStillPublishesItsFrames() throws Exception {
        final SeekbarPreviewThumbnailHolder holder = new SeekbarPreviewThumbnailHolder();
        final UUID current = UUID.randomUUID();
        setRequest(holder, current);
        method("generateDataFrom", Frameset.class, UUID.class)
                .invoke(holder, oneMissingPage(), current);
        assertEquals(1, data(holder).size());
    }

    @Test
    public void resetDuringBitmapCropCannotReturnTheOldItemsPreview() throws Exception {
        final SeekbarPreviewThumbnailHolder holder = new SeekbarPreviewThumbnailHolder();
        final CountDownLatch cropStarted = new CountDownLatch(1);
        final CountDownLatch allowCrop = new CountDownLatch(1);
        final AtomicReference<Bitmap> produced = new AtomicReference<>();
        final AtomicReference<java.util.Optional<Bitmap>> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        data(holder).put(0, () -> {
            cropStarted.countDown();
            try {
                if (!allowCrop.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("crop remained blocked");
                }
            } catch (final InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            final Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
            produced.set(bitmap);
            return bitmap;
        });
        final Thread getter = new Thread(() -> {
            try {
                result.set(holder.getBitmapAt(0));
            } catch (final Throwable failure) {
                error.set(failure);
            }
        }, "preview-test-crop");
        getter.start();
        assertTrue(cropStarted.await(3, TimeUnit.SECONDS));
        setRequest(holder, UUID.randomUUID());
        allowCrop.countDown();
        getter.join(3000);
        assertFalse(getter.isAlive());
        assertNull(error.get());
        assertTrue("old thumbnail was delivered after selection changed", result.get().isEmpty());
        assertTrue("discarded crop was not recycled", produced.get().isRecycled());
    }

    @Test
    public void currentPreviewCropIsReturnedWithoutRecycling() throws Exception {
        final SeekbarPreviewThumbnailHolder holder = new SeekbarPreviewThumbnailHolder();
        final Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        try {
            data(holder).put(0, () -> bitmap);
            assertSame(bitmap, holder.getBitmapAt(0).orElseThrow());
            assertFalse(bitmap.isRecycled());
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    public void undersizedStoryboardReturnsNoFrameInsteadOfCroppingOutsideBitmap()
            throws Exception {
        final Bitmap source = Bitmap.createBitmap(40, 50, Bitmap.Config.ARGB_8888);
        try {
            final Supplier<Bitmap> crop = supplier(source, 50, 50);
            assertNull(crop.get());
        } finally {
            source.recycle();
        }
    }

    @Test
    public void wholeFrameCropKeepsAnIndependentBitmap() throws Exception {
        final Bitmap source = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888);
        final Bitmap result = supplier(source, 50, 50).get();
        try {
            assertNotSame(source, result);
            assertEquals(50, result.getWidth());
            assertEquals(50, result.getHeight());
            assertTrue(!result.isRecycled());
        } finally {
            result.recycle();
            source.recycle();
        }
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

    private static Method method(final String name, final Class<?>... parameters)
            throws Exception {
        final Method result = SeekbarPreviewThumbnailHolder.class
                .getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    private static Frameset oneMissingPage() {
        return new Frameset(Arrays.asList((String) null), 10, 10, 1, 1000, 1, 1);
    }

    @SuppressWarnings("unchecked")
    private static Supplier<Bitmap> supplier(final Bitmap bitmap, final int frameWidth,
                                             final int frameHeight) throws Exception {
        final Frameset frameset = new Frameset(List.of(), frameWidth, frameHeight,
                1, 1000, 1, 1);
        return (Supplier<Bitmap>) method("createBitmapSupplier", Bitmap.class, int[].class,
                Frameset.class).invoke(new SeekbarPreviewThumbnailHolder(), bitmap,
                new int[]{0, 0, 0, frameWidth, frameHeight}, frameset);
    }
}
