package org.schabi.newpipe.player.mediabrowser;

import android.support.v4.media.session.PlaybackStateCompat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Malformed generated media IDs must reach the existing MediaSession error callback. */
@RunWith(AndroidJUnit4.class)
public class MediaBrowserMalformedIdDeviceTest {
    @Test
    public void malformedNumericIdReportsErrorWithoutThrowingToCaller() throws Exception {
        assertMalformedIdReported("playlists/local/not-a-number/0");
    }

    @Test
    public void unknownItemTypeReportsErrorWithoutThrowingToCaller() throws Exception {
        assertMalformedIdReported("item/unknown/0?url=https%3A%2F%2Fexample.invalid%2Fmedia");
    }

    private static void assertMalformedIdReported(final String mediaId) throws Exception {
        final CountDownLatch reported = new CountDownLatch(1);
        final AtomicInteger code = new AtomicInteger(-1);
        final AtomicInteger prepares = new AtomicInteger();
        final MediaBrowserPlaybackPreparer preparer = new MediaBrowserPlaybackPreparer(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                (message, errorCode) -> {
                    code.set(errorCode);
                    reported.countDown();
                }, () -> { }, ignored -> prepares.incrementAndGet());
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> preparer.onPrepareFromMediaId(mediaId, true, null));
            assertTrue("error callback was not reached", reported.await(10, TimeUnit.SECONDS));
            assertEquals(PlaybackStateCompat.ERROR_CODE_APP_ERROR, code.get());
            assertEquals(0, prepares.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(preparer::dispose);
        }
    }
}
