package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.localization.ContentCountry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only owned media, real service restore, controller, and network boundaries. */
final class SavedPlaybackTestSupport {
    private SavedPlaybackTestSupport() { }

    static Player restoreSavedService(final Context context, final PlayerService service)
            throws Exception {
        runOnMain(service::destroyPlayerAndStopService);
        androidx.core.content.ContextCompat.startForegroundService(context,
                new Intent(context, PlayerService.class)
                        .setAction(PlayerService.ACTION_RESTORE_LAST_SESSION)
                        .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        assertTrue("restore intent did not create saved player", waitFor(() -> callOnMain(() ->
                service.getPlayer() != null && service.getPlayer().getPlayQueue() != null), 10));
        return callOnMain(service::getPlayer);
    }

    static void assertControllerPaused(final Context context, final Player player,
                                       final String savedUrl) throws Exception {
        if (callOnMain(SavedPlaybackTestSupport::activeQueueActivity) == null) {
            InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(context, PlayQueueActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        assertTrue("saved controller did not resume", waitFor(
                () -> callOnMain(() -> controllerReady(player)), 10));
        final PlayQueueActivity controller =
                callOnMain(SavedPlaybackTestSupport::activeQueueActivity);
        runOnMain(() -> {
            final android.view.View metadata = controller.findViewById(R.id.metadata);
            assertTrue("saved expand accessibility action was absent",
                    metadata.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND,
                            null));
            assertTrue("repeated saved expand action was blocked",
                    metadata.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND,
                            null));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(controller, callOnMain(SavedPlaybackTestSupport::activeQueueActivity));
        assertEquals(savedUrl, callOnMain(player::getVideoUrl));
        assertFalse("saved controller expansion restarted playback",
                callOnMain(player::getPlayWhenReady));
    }

    static boolean controllerReady(final Player player) {
        final PlayQueueActivity activity = activeQueueActivity();
        if (activity == null || player.getPlayQueue() == null
                || player.getPlayQueue().getItem() == null) {
            return false;
        }
        final TextView title = activity.findViewById(R.id.song_name);
        final android.view.View metadata = activity.findViewById(R.id.metadata);
        if (title == null || metadata == null
                || !player.getPlayQueue().getItem().getTitle().contentEquals(title.getText())) {
            return false;
        }
        final AccessibilityNodeInfo node = metadata.createAccessibilityNodeInfo();
        try {
            return node.getActionList().stream().anyMatch(action ->
                    action.getId() == AccessibilityNodeInfo.ACTION_EXPAND);
        } finally {
            node.recycle();
        }
    }

    static final class RejectNetwork extends Downloader implements AutoCloseable {
        private final Downloader previous = NewPipe.getDownloader();
        private final Localization localization = NewPipe.getPreferredLocalization();
        private final ContentCountry country = NewPipe.getPreferredContentCountry();
        final AtomicInteger requests = new AtomicInteger();

        RejectNetwork() {
            NewPipe.init(this, localization, country);
        }

        @Override
        public Response execute(final Request request) throws IOException {
            requests.incrementAndGet();
            throw new IOException("Network disabled for saved-playback regression");
        }

        @Override
        public void close() {
            NewPipe.init(previous, localization, country);
        }
    }

    static File createSilentWav(final Context context) throws IOException {
        return createSilentWav(context, 4);
    }

    static File createSilentWav(final Context context, final int seconds) throws IOException {
        final File wav = File.createTempFile("personal-playback-", ".wav", context.getCacheDir());
        final int sampleRate = 8000;
        final int dataLength = sampleRate * seconds * 2;
        try (FileOutputStream output = new FileOutputStream(wav)) {
            writeAscii(output, "RIFF");
            writeInt(output, 36 + dataLength);
            writeAscii(output, "WAVEfmt ");
            writeInt(output, 16);
            writeShort(output, 1);
            writeShort(output, 1);
            writeInt(output, sampleRate);
            writeInt(output, sampleRate * 2);
            writeShort(output, 2);
            writeShort(output, 16);
            writeAscii(output, "data");
            writeInt(output, dataLength);
            output.write(new byte[dataLength]);
        }
        return wav;
    }

    private static void writeAscii(final FileOutputStream output, final String value)
            throws IOException {
        output.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeInt(final FileOutputStream output, final int value)
            throws IOException {
        output.write(value);
        output.write(value >>> 8);
        output.write(value >>> 16);
        output.write(value >>> 24);
    }

    private static void writeShort(final FileOutputStream output, final int value)
            throws IOException {
        output.write(value);
        output.write(value >>> 8);
    }

    static PlayQueueActivity activeQueueActivity() {
        for (final android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (activity instanceof PlayQueueActivity) {
                return (PlayQueueActivity) activity;
            }
        }
        return null;
    }

    static boolean waitFor(final Check check, final long timeoutSeconds)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (check.matches()) {
                return true;
            }
            Thread.sleep(25);
        }
        return check.matches();
    }

    static void runOnMain(final ThrowingRunnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                action.run();
            } catch (final Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    static <T> T callOnMain(final ThrowingSupplier<T> action) {
        final Object[] result = new Object[1];
        runOnMain(() -> result[0] = action.get());
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    interface Check {
        boolean matches();
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
