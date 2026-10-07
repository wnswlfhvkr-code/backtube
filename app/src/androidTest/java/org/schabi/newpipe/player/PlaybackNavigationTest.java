package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.App;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.ui.MainPlayerUi;
import org.schabi.newpipe.player.ui.PopupPlayerUi;
import org.schabi.newpipe.util.InfoCache;
import org.schabi.newpipe.util.NavigationHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real activity/gesture dispatch using generated local audio and cached synthetic metadata. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class PlaybackNavigationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final Map<String, Boolean> savedPreferences = new HashMap<>();
    private PlayerService service;
    private Player player;
    private MainActivity activity;
    private File audio;
    private File orientationVideo;
    private boolean bound;
    private boolean notificationsRequested;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).getService();
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            service = null;
        }
    };

    @Before
    public void setUp() throws Exception {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        for (final int key : new int[]{R.string.auto_queue_key, R.string.show_comments_key,
                R.string.data_saver_key,
                R.string.start_main_player_fullscreen_key}) {
            final String name = context.getString(key);
            savedPreferences.put(name, preferences.contains(name)
                    ? preferences.getBoolean(name, false) : null);
            preferences.edit().putBoolean(name, false).commit();
        }
        // Notification permission behavior has separate UI coverage; keep this fixture's input
        // dispatch focused on player navigation without modifying the OS permission grant.
        notificationsRequested = App.getInstance().getNotificationsRequested();
        App.getInstance().setNotificationsRequested();
        audio = File.createTempFile("navigation-", ".wav", context.getCacheDir());
        final int length = 8000 * 60 * 2;
        final ByteBuffer wav = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length);
        try (FileOutputStream output = new FileOutputStream(audio)) {
            output.write(wav.array());
        }
        ContextCompat.startForegroundService(context, new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue(connected.await(10, TimeUnit.SECONDS));
        activity = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(() -> {
            player = service.getPlayer();
            cache("navigation-A");
            cache("navigation-B");
            final SinglePlayQueue queue = new SinglePlayQueue(List.of(
                    new StreamInfoItem(1, "navigation-A", "navigation-A", StreamType.AUDIO_STREAM),
                    new StreamInfoItem(1, "navigation-B", "navigation-B", StreamType.AUDIO_STREAM)),
                    1);
            // Start through the same single navigation request as a user selection. Sending a
            // second direct player intent first races fragment stop/reuse against initial loading.
            NavigationHelper.openVideoDetailFragment(activity, activity.getSupportFragmentManager(),
                    1, "navigation-B", "navigation-B", queue, false, true);
        });
        await(() -> player.isPlaying() && "navigation-B".equals(player.getVideoUrl())
                && sheetState() == BottomSheetBehavior.STATE_EXPANDED
                && activity.findViewById(R.id.playPauseButton) != null);
        onMain(() -> {
            player.getExoPlayer().seekTo(10000);
            player.setSleepTimer(60_000);
        });
    }

    @After
    public void tearDown() throws Exception {
        onMain(() -> {
            if (activity != null && !activity.isDestroyed()) {
                final VideoDetailFragment detail = (VideoDetailFragment) activity
                        .getSupportFragmentManager().findFragmentById(R.id.fragment_player_holder);
                if (detail != null) {
                    detail.prepareForUiExit();
                }
                activity.finish();
            }
            if (service != null) {
                service.destroyPlayerAndStopService();
            }
        });
        if (bound) {
            context.unbindService(connection);
        }
        final Field requested = App.class.getDeclaredField("notificationsRequested");
        requested.setAccessible(true);
        requested.setBoolean(App.getInstance(), notificationsRequested);
        final SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(context).edit();
        savedPreferences.forEach((key, value) -> {
            if (value == null) {
                editor.remove(key);
            } else {
                editor.putBoolean(key, value);
            }
        });
        editor.commit();
        if (audio != null) {
            audio.delete();
        }
        if (orientationVideo != null) {
            orientationVideo.delete();
        }
        InfoCache.getInstance().removeInfo(1, "orientation-video", InfoCache.Type.STREAM);
        InfoCache.getInstance().removeInfo(1, "navigation-A", InfoCache.Type.STREAM);
        InfoCache.getInstance().removeInfo(1, "navigation-B", InfoCache.Type.STREAM);
    }

    @Test
    public void backCollapsesWithoutPreviousTrackAndDoubleBackRemovesOnlyUi() throws Exception {
        pressBack();
        await(() -> sheetState() == BottomSheetBehavior.STATE_COLLAPSED);
        onMain(() -> {
            assertEquals(1, player.getPlayQueue().getIndex());
            assertEquals("navigation-B", player.getVideoUrl());
            assertTrue(player.isPlaying());
            assertTrue(player.getExoPlayer().getCurrentPosition() >= 10000);
        });
        pressBack();
        onMain(() -> {
            assertFalse(activity.isFinishing());
            final android.widget.TextView notice = activity.findViewById(
                    com.google.android.material.R.id.snackbar_text);
            assertEquals("한 번 더 뒤로가면 종료됩니다", notice.getText().toString());
        });
        pressBack();
        await(activity::isDestroyed);
        onMain(() -> {
            assertSame(player, service.getPlayer());
            assertEquals(PlayerType.AUDIO, player.getPlayerType());
            assertTrue(player.isPlaying());
            assertTrue(player.getSleepTimerRemainingMillis() > 0);
            assertEquals(1, player.getPlayQueue().getIndex());
        });
    }

    @Test
    public void fullscreenBackCollapsesWithoutPausingOrRewinding() throws Exception {
        onMain(() -> player.UIs().get(MainPlayerUi.class).orElseThrow().toggleFullscreen());
        pressBack();
        await(() -> sheetState() == BottomSheetBehavior.STATE_COLLAPSED);
        onMain(() -> {
            assertTrue(player.isPlaying());
            assertEquals(1, player.getPlayQueue().getIndex());
            assertFalse(player.UIs().get(MainPlayerUi.class).orElseThrow().isFullscreen());
        });
    }

    @Test
    public void expiredPromptRequiresTwoNewBackPresses() throws Exception {
        pressBack();
        await(() -> sheetState() == BottomSheetBehavior.STATE_COLLAPSED);
        pressBack();
        SystemClock.sleep(1200);
        pressBack();
        onMain(() -> assertFalse(activity.isFinishing()));
        pressBack();
        await(activity::isDestroyed);
    }

    @Test
    public void centerDownwardSwipeCollapsesWhileTapAndSeekRemainControls() throws Exception {
        onMain(() -> player.UIs().get(MainPlayerUi.class).orElseThrow().showControls(0));
        final Rect button = bounds(R.id.playPauseButton);
        touch(button.centerX(), button.centerY(), button.centerX(), button.centerY());
        await(() -> !player.getPlayWhenReady());
        assertExpanded();
        final Rect seek = bounds(R.id.playbackSeekBar);
        touch(seek.left + seek.width() / 4f, seek.centerY(),
                seek.left + seek.width() / 2f, seek.centerY());
        assertExpanded();
        touch(button.centerX(), button.centerY(), button.centerX(),
                activity.getResources().getDisplayMetrics().heightPixels - 80);
        await(() -> sheetState() == BottomSheetBehavior.STATE_COLLAPSED);
        onMain(() -> {
            assertFalse(player.getPlayWhenReady());
            assertEquals(1, player.getPlayQueue().getIndex());
        });
    }

    @Test
    public void popupExpansionKeepsPortraitPolicyAndControlsReachable() throws Exception {
        assertPopupExpansionOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                Configuration.ORIENTATION_PORTRAIT, false);
    }

    @Test
    public void popupExpansionKeepsLandscapePolicyAndControlsReachable() throws Exception {
        assertPopupExpansionOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                Configuration.ORIENTATION_LANDSCAPE, false);
    }

    @Test
    public void popupExpansionKeepsLockedPortraitPolicyAndControlsReachable() throws Exception {
        assertPopupExpansionOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                Configuration.ORIENTATION_PORTRAIT, true);
    }

    private void assertPopupExpansionOrientation(final int requested, final int configuration,
                                                final boolean lock) throws Exception {
        // Disposable emulator setup only. Restore the exact app-op mode even on assertion failure;
        // system auto-rotation and the user's orientation preference are never changed.
        final String operation = "appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW ";
        final String existing = shell("appops get " + context.getPackageName()
                + " SYSTEM_ALERT_WINDOW");
        final Matcher mode = Pattern.compile("SYSTEM_ALERT_WINDOW: ([a-z_]+)").matcher(existing);
        final String originalMode = mode.find() ? mode.group(1) : "default";
        try {
            shell(operation + "allow");
            assertTrue(android.provider.Settings.canDrawOverlays(context));
            onMain(() -> activity.setRequestedOrientation(requested));
            await(() -> refreshActivity(configuration));
            if (lock) {
                onMain(() -> activity.setRequestedOrientation(
                        ActivityInfo.SCREEN_ORIENTATION_LOCKED));
                await(() -> refreshActivity(configuration));
            }
            final int policy = lock ? ActivityInfo.SCREEN_ORIENTATION_LOCKED : requested;
            prepareOrientationVideo();
            onMain(() -> player.handleIntent(NavigationHelper.getPlayerIntent(context,
                    PlayerService.class, player.getPlayQueue(), PlayerIntentType.AllOthers)
                    .putExtra(Player.PLAYER_TYPE, PlayerType.POPUP)
                    .putExtra(Player.PLAY_WHEN_READY, true)));
            await(() -> player.UIs().get(PopupPlayerUi.class)
                    .map(ui -> ui.getBinding().getRoot().isAttachedToWindow()).orElse(false));
            onMain(() -> player.UIs().get(PopupPlayerUi.class).orElseThrow()
                    .getBinding().fullScreenButton.performClick());
            await(() -> refreshActivity(configuration) && player.getPlayerType() == PlayerType.MAIN
                    && player.UIs().get(MainPlayerUi.class).isPresent()
                    && activity.findViewById(R.id.listeningMode) != null);
            onMain(() -> {
                assertEquals(policy, activity.getRequestedOrientation());
                assertEquals(configuration, activity.getResources().getConfiguration().orientation);
                assertTrue(player.isPlaying());
                player.UIs().get(MainPlayerUi.class).orElseThrow().showControls(0);
            });
            assertControlsReachable();
            captureOrientationScreen(lock ? "popup-expanded-portrait-locked.png"
                    : configuration == Configuration.ORIENTATION_PORTRAIT
                    ? "popup-expanded-portrait.png" : "popup-expanded-landscape.png");
        } finally {
            // A failed expansion may leave an overlay attached. Remove it before revoking access.
            try {
                onMain(() -> player.UIs().destroyAll(PopupPlayerUi.class));
            } finally {
                shell(operation + originalMode);
            }
        }
    }

    private void prepareOrientationVideo() throws Exception {
        orientationVideo = File.createTempFile("orientation-", ".mp4", context.getCacheDir());
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("listening-mode-fixture.mp4");
             FileOutputStream output = new FileOutputStream(orientationVideo)) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        onMain(() -> {
            final StreamInfo info = new StreamInfo(1, "orientation-video", "orientation-video",
                    StreamType.VIDEO_STREAM, "Generated orientation video", "", 0);
            info.setDuration(30);
            info.setUploaderName("Local orientation fixture");
            info.setVideoStreams(List.of(new VideoStream.Builder().setId("local-video")
                    .setContent(Uri.fromFile(orientationVideo).toString(), true)
                    .setMediaFormat(MediaFormat.MPEG_4).setResolution("90p")
                    .setIsVideoOnly(false).build()));
            info.setAudioStreams(List.of());
            info.setRelatedItems(List.of());
            InfoCache.getInstance().putInfo(1, "orientation-video", info, InfoCache.Type.STREAM);
            NavigationHelper.openVideoDetailFragment(activity, activity.getSupportFragmentManager(),
                    1, "orientation-video", info.getName(), new SinglePlayQueue(info), false, true);
        });
        await(() -> player.isPlaying() && "orientation-video".equals(player.getVideoUrl()));
    }

    private boolean refreshActivity(final int orientation) {
        for (final Activity candidate : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (candidate instanceof MainActivity && !candidate.isFinishing()) {
                activity = (MainActivity) candidate;
                return activity.getResources().getConfiguration().orientation == orientation;
            }
        }
        return false;
    }

    private void assertControlsReachable() throws Exception {
        await(() -> activity.findViewById(R.id.primaryControlsScroll).getWidth() > 0);
        onMain(() -> {
            final HorizontalScrollView scroll = activity.findViewById(R.id.primaryControlsScroll);
            final View quality = activity.findViewById(R.id.qualityTextView);
            final View listening = activity.findViewById(R.id.listeningMode);
            assertEquals(View.VISIBLE, quality.getVisibility());
            assertEquals(View.VISIBLE, listening.getVisibility());
            assertTrue("quality/listening order changed",
                    quality.getRight() <= listening.getLeft());
            final Rect viewport = new Rect();
            assertTrue(scroll.getGlobalVisibleRect(viewport));
            for (final View control : new View[]{quality, listening}) {
                scroll.scrollTo(control.getLeft(), 0);
                final Rect visible = new Rect();
                assertTrue("control is clipped after popup expansion",
                        control.getGlobalVisibleRect(visible));
                assertEquals("control cannot be fully reached",
                        control.getWidth(), visible.width());
                assertTrue(viewport.contains(visible));
            }
            scroll.scrollTo(0, 0);
        });
    }

    private void captureOrientationScreen(final String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        final Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        final File externalFiles = context.getExternalFilesDir(null);
        assertNotNull(externalFiles);
        final File directory = new File(externalFiles, "personal-playback-test");
        assertTrue(directory.exists() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
    }

    private static String shell(final String command) throws Exception {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void assertExpanded() {
        onMain(() -> assertEquals(BottomSheetBehavior.STATE_EXPANDED, sheetState()));
    }

    private void cache(final String id) {
        final StreamInfo info = new StreamInfo(1, id, id, StreamType.AUDIO_STREAM, id, id, 0);
        info.setDuration(60);
        info.setUploaderName("Local navigation fixture");
        info.setAudioStreams(List.of(new AudioStream.Builder().setId(id)
                .setContent(Uri.fromFile(audio).toString(), true)
                .setMediaFormat(MediaFormat.WAV).setAverageBitrate(128).build()));
        info.setRelatedItems(List.of());
        InfoCache.getInstance().putInfo(1, id, info, InfoCache.Type.STREAM);
    }

    private int sheetState() {
        return BottomSheetBehavior.from(activity.findViewById(R.id.fragment_player_holder))
                .getState();
    }

    private Rect bounds(final int id) {
        final Rect bounds = new Rect();
        onMain(() -> {
            final View view = activity.findViewById(id);
            assertTrue("control not visible: " + id, view.getGlobalVisibleRect(bounds));
        });
        return bounds;
    }

    private static void pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void touch(final float fromX, final float fromY,
                              final float toX, final float toY) {
        final long start = SystemClock.uptimeMillis();
        for (int index = 0; index <= 12; index++) {
            final float fraction = index / 12f;
            final int action = index == 0 ? MotionEvent.ACTION_DOWN
                    : index == 12 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            final MotionEvent event = MotionEvent.obtain(start, start + index * 25L, action,
                    fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction, 0);
            InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
            event.recycle();
            SystemClock.sleep(25);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void await(final BooleanSupplier check) throws Exception {
        final long deadline = SystemClock.uptimeMillis() + 10000;
        final boolean[] ready = {false};
        do {
            onMain(() -> ready[0] = check.getAsBoolean());
            if (ready[0]) {
                return;
            }
            Thread.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("navigation condition timed out", ready[0]);
    }

    private static void onMain(final Runnable action) {
        final FutureTask<Void> task = new FutureTask<>(action, null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        try {
            task.get();
        } catch (final Exception error) {
            throw new AssertionError("Main-thread navigation check failed", error);
        }
    }
}
