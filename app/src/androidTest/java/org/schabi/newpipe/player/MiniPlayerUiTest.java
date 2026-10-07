package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import coil3.transition.CrossfadeDrawable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.helper.LastPlaybackSessionStore;
import org.schabi.newpipe.player.gesture.SwipeUpToOpenListener;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.InfoCache;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Local-only artwork, gesture and navigation coverage for the current-track row. */
@RunWith(AndroidJUnit4.class)
public class MiniPlayerUiTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<File> fixtures = new ArrayList<>();
    private final List<StreamInfo> tracks = new ArrayList<>();
    private final CountDownLatch connected = new CountDownLatch(1);
    private PlayerService service;
    private Player player;
    private boolean bound;
    private Boolean previousAutoQueue;
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
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String autoQueueKey = context.getString(R.string.auto_queue_key);
        previousAutoQueue = prefs.contains(autoQueueKey) ? prefs.getBoolean(autoQueueKey, false)
                : null;
        ContextCompat.startForegroundService(context, new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue(connected.await(10, TimeUnit.SECONDS));
        await(() -> service.getPlayer() != null);
        player = service.getPlayer();
        final File audio = fixture(".wav");
        final int length = 8000 * 30 * 2;
        final ByteBuffer wav = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length);
        try (FileOutputStream out = new FileOutputStream(audio)) {
            out.write(wav.array());
        }
        for (int i = 0; i < 30; i++) {
            final String id = "mini-player-local-" + i;
            final StreamInfo info = new StreamInfo(1, id, id, StreamType.AUDIO_STREAM, id, id, 0);
            info.setDuration(30);
            info.setUploaderName("Local UI fixture");
            info.setAudioStreams(List.of(new AudioStream.Builder().setId(id)
                    .setContent(Uri.fromFile(audio).toString(), true)
                    .setMediaFormat(MediaFormat.WAV).setAverageBitrate(128).build()));
            info.setRelatedItems(List.of());
            if (i < 2) {
                final File cover = fixture(".png");
                final Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(i == 0 ? Color.RED : Color.BLUE);
                try (FileOutputStream out = new FileOutputStream(cover)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                bitmap.recycle();
                info.setThumbnails(List.of(new Image(Uri.fromFile(cover).toString(),
                        32, 32, Image.ResolutionLevel.LOW)));
            }
            tracks.add(info);
            InfoCache.getInstance().putInfo(1, id, info, InfoCache.Type.STREAM);
        }
        onMain(() -> {
            player.setAutoQueueEnabled(false);
            final Method initPlayer = Player.class.getDeclaredMethod("initPlayer", boolean.class);
            initPlayer.setAccessible(true);
            initPlayer.invoke(player, false);
            final Field playerType = Player.class.getDeclaredField("playerType");
            playerType.setAccessible(true);
            playerType.set(player, PlayerType.AUDIO);
            final Method init = Player.class.getDeclaredMethod("initPlayback",
                    PlayQueue.class, boolean.class);
            init.setAccessible(true);
            final SinglePlayQueue queue = new SinglePlayQueue(tracks.get(0));
            for (int i = 1; i < tracks.size(); i++) {
                queue.append(new SinglePlayQueue(tracks.get(i)).getStreams());
            }
            init.invoke(player, queue, false);
        });
        final Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PlayQueueActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        await(() -> active(PlayQueueActivity.class) != null
                && active(PlayQueueActivity.class).getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT
                && currentRowLaidOut()
                && tracks.get(0).getName().contentEquals(((TextView) active(
                        PlayQueueActivity.class).findViewById(R.id.song_name)).getText()));
    }

    @After
    public void tearDown() {
        onMain(() -> {
            for (final Stage stage : List.of(Stage.RESUMED, Stage.STARTED,
                    Stage.PAUSED, Stage.STOPPED)) {
                for (final Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(stage))) {
                    if (!activity.isFinishing() && (activity instanceof MainActivity
                            || activity instanceof PlayQueueActivity)) {
                        activity.finish();
                    }
                }
            }
            if (service != null) {
                service.destroyPlayerAndStopService();
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        if (bound) {
            context.unbindService(connection);
        }
        new LastPlaybackSessionStore(
                PreferenceManager.getDefaultSharedPreferences(context)).clear();
        for (final StreamInfo track : tracks) {
            InfoCache.getInstance().removeInfo(1, track.getUrl(), InfoCache.Type.STREAM);
        }
        final SharedPreferences.Editor prefs = PreferenceManager
                .getDefaultSharedPreferences(context).edit();
        final String autoQueueKey = context.getString(R.string.auto_queue_key);
        if (previousAutoQueue == null) {
            prefs.remove(autoQueueKey);
        } else {
            prefs.putBoolean(autoQueueKey, previousAutoQueue);
        }
        prefs.commit();
        for (final File file : fixtures) {
            file.delete();
        }
    }

    @Test
    public void currentArtworkFollowsTrackAndNextPreviewHasNoArtwork() throws Exception {
        onMain(() -> active(PlayQueueActivity.class).setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        await(() -> active(PlayQueueActivity.class) != null
                && active(PlayQueueActivity.class).getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT
                && currentRowLaidOut()
                && Integer.valueOf(Color.RED).equals(artworkColor()));
        onMain(this::assertCurrentRowVisible);
        captureScreen("backtube-current-track-generated.png");
        rotateQueueToLandscape();
        onMain(this::assertCurrentRowVisible);
        captureScreen("backtube-current-track-generated-landscape.png");
        onMain(() -> {
            final View preview = active(PlayQueueActivity.class)
                    .findViewById(R.id.recommendation_preview);
            assertFalse("next-track preview still contains an image", containsImage(preview));
            player.playNext();
        });
        await(() -> Integer.valueOf(Color.BLUE).equals(artworkColor()));
        onMain(player::playNext);
        await(() -> {
            final Integer color = artworkColor();
            return tracks.get(2).getName().contentEquals(((TextView) active(
                    PlayQueueActivity.class).findViewById(R.id.song_name)).getText())
                    && color != null && color != Color.BLUE && color != Color.RED;
        });
    }

    @Test
    public void swipeReopensSameQueueAndButtonsAndListRemainUsable() throws Exception {
        final PlayQueue[] original = new PlayQueue[1];
        onMain(() -> original[0] = player.getPlayQueue());
        for (int repeat = 0; repeat < 3; repeat++) {
            final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            final Instrumentation.ActivityMonitor launches = instrumentation.addMonitor(
                    MainActivity.class.getName(), null, false);
            try {
                onMain(() -> {
                    final View row = active(PlayQueueActivity.class).findViewById(R.id.metadata);
                    // Both complete before the activity can pause: only one launch is allowed.
                    swipe(row, 0, -160, false);
                    swipe(row, 0, -160, false);
                });
                await(() -> active(MainActivity.class) != null
                        && BottomSheetBehavior.from(active(MainActivity.class)
                        .findViewById(R.id.fragment_player_holder)).getState()
                        == BottomSheetBehavior.STATE_EXPANDED);
                assertEquals("rapid repeated swipes launched the player twice", 1,
                        launches.getHits());
            } finally {
                instrumentation.removeMonitor(launches);
            }
            onMain(() -> {
                assertSame(original[0], player.getPlayQueue());
                active(MainActivity.class).onBackPressed();
            });
            await(() -> active(MainActivity.class) != null
                    && BottomSheetBehavior.from(active(MainActivity.class)
                    .findViewById(R.id.fragment_player_holder)).getState()
                    == BottomSheetBehavior.STATE_COLLAPSED);
            onMain(() -> active(MainActivity.class)
                    .findViewById(R.id.overlay_play_queue_button).performClick());
            await(() -> active(PlayQueueActivity.class) != null
                    && active(PlayQueueActivity.class).findViewById(R.id.metadata).getWidth() > 0);
        }
        onMain(() -> {
            final PlayQueueActivity activity = active(PlayQueueActivity.class);
            swipe(activity.findViewById(R.id.metadata), 160, -10, false);
            swipe(activity.findViewById(R.id.metadata), 0, 160, false);
            swipe(activity.findViewById(R.id.metadata), 0, -160, true);
            assertNull(active(MainActivity.class));
            player.pause();
            activity.findViewById(R.id.control_play_pause).performClick();
            assertTrue(player.getExoPlayer().getPlayWhenReady());
            activity.findViewById(R.id.control_play_pause).performClick();
            assertFalse(player.getExoPlayer().getPlayWhenReady());
            final RecyclerView list = activity.findViewById(R.id.play_queue);
            assertTrue(list.canScrollVertically(1));
            final int before = list.computeVerticalScrollOffset();
            swipe(list, 0, -160, false);
            assertTrue("list swipe was intercepted", list.computeVerticalScrollOffset() > before);
        });
    }

    @Test
    public void landscapeVisibleMetadataAcceptsScreenCoordinateSwipe() throws Exception {
        rotateQueueToLandscape();
        final Rect row = new Rect();
        final PlayQueue[] original = new PlayQueue[1];
        onMain(() -> {
            assertCurrentRowVisible();
            original[0] = player.getPlayQueue();
            final View metadata = active(PlayQueueActivity.class).findViewById(R.id.metadata);
            final int[] location = new int[2];
            metadata.getLocationOnScreen(location);
            row.set(location[0], location[1], location[0] + metadata.getWidth(),
                    location[1] + metadata.getHeight());
        });
        final float density = context.getResources().getDisplayMetrics().density;
        final float startY = row.bottom - 8 * density;
        final long start = SystemClock.uptimeMillis();
        for (int i = 0; i <= 8; i++) {
            final MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(),
                    i == 0 ? MotionEvent.ACTION_DOWN : i == 8 ? MotionEvent.ACTION_UP
                            : MotionEvent.ACTION_MOVE,
                    row.centerX(), startY - 120 * density * i / 8f, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try {
                assertTrue("screen-coordinate gesture could not be injected",
                        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                                .injectInputEvent(event, true));
            } finally {
                event.recycle();
            }
            SystemClock.sleep(20);
        }
        await(() -> active(MainActivity.class) != null
                && BottomSheetBehavior.from(active(MainActivity.class)
                .findViewById(R.id.fragment_player_holder)).getState()
                == BottomSheetBehavior.STATE_EXPANDED);
        onMain(() -> assertSame(original[0], player.getPlayQueue()));
    }

    private void rotateQueueToLandscape() throws Exception {
        final PlayQueueActivity[] previous = new PlayQueueActivity[1];
        final boolean[] recreationExpected = new boolean[1];
        onMain(() -> {
            previous[0] = active(PlayQueueActivity.class);
            recreationExpected[0] = previous[0].getResources().getConfiguration().orientation
                    != Configuration.ORIENTATION_LANDSCAPE;
            previous[0].setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        });
        // Resources can report landscape on the old activity before recreation completes.
        await(() -> active(PlayQueueActivity.class) != null
                && (!recreationExpected[0] || active(PlayQueueActivity.class) != previous[0])
                && active(PlayQueueActivity.class).getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE
                && currentRowLaidOut()
                && Integer.valueOf(Color.RED).equals(artworkColor()));
    }

    private boolean currentRowLaidOut() {
        final PlayQueueActivity activity = active(PlayQueueActivity.class);
        if (activity == null || !activity.hasWindowFocus()
                || activity.getWindow().getDecorView().isLayoutRequested()) {
            return false;
        }
        final View row = activity.findViewById(R.id.metadata);
        // Cached artwork can arrive before the recreated activity's first layout.
        return row.isAttachedToWindow() && row.isShown() && row.isLaidOut()
                && row.getWindowVisibility() == View.VISIBLE && !row.isLayoutRequested()
                && row.getWidth() > 0 && row.getHeight() > 0
                && row.getGlobalVisibleRect(new Rect());
    }

    private void assertCurrentRowVisible() {
        final PlayQueueActivity activity = active(PlayQueueActivity.class);
        final Rect row = fullyVisibleBounds(activity.findViewById(R.id.metadata), "current row");
        final Rect artwork = fullyVisibleBounds(activity.findViewById(
                R.id.current_track_thumbnail), "current artwork");
        assertTrue("artwork lies outside its current row", row.contains(artwork));
        assertTrue("title lies outside its current row", row.contains(fullyVisibleBounds(
                activity.findViewById(R.id.song_name), "current title")));
        assertTrue("artist lies outside its current row", row.contains(fullyVisibleBounds(
                activity.findViewById(R.id.artist_name), "current artist")));
        fullyVisibleBounds(activity.findViewById(R.id.control_play_pause), "play/pause");
        final Rect queue = fullyVisibleBounds(activity.findViewById(R.id.play_queue), "queue");
        assertTrue("current row overlaps the queue", !Rect.intersects(row, queue));
    }

    private static Rect fullyVisibleBounds(final View view, final String name) {
        final Rect bounds = new Rect();
        assertTrue(name + " is hidden or empty: shown=" + view.isShown()
                + ", size=" + view.getWidth() + "x" + view.getHeight(),
                view.isShown() && view.getWidth() > 0
                && view.getHeight() > 0 && view.getGlobalVisibleRect(bounds));
        assertEquals(name + " is clipped horizontally", view.getWidth(), bounds.width());
        assertEquals(name + " is clipped vertically", view.getHeight(), bounds.height());
        return bounds;
    }

    @Test
    public void gesturesRejectWrongDirectionMultitouchAndCancellationButPreserveTap() {
        onMain(() -> {
            final View row = new View(context);
            row.layout(0, 0, 600, 200);
            final int[] opensAndClicks = new int[2];
            row.setOnClickListener(view -> opensAndClicks[1]++);
            row.setOnTouchListener(new SwipeUpToOpenListener(row, () -> opensAndClicks[0]++));
            swipe(row, 0, 0, false);
            assertEquals(1, opensAndClicks[1]);
            swipe(row, 160, -10, false);
            swipe(row, 0, 160, false);
            swipe(row, 0, -160, true);
            final long canceledStart = SystemClock.uptimeMillis();
            dispatchPointers(row, canceledStart, 0, MotionEvent.ACTION_UP, 1, 160);
            twoFingerSwipe(row, false);
            twoFingerSwipe(row, true);
            assertEquals(0, opensAndClicks[0]);
            assertEquals(1, opensAndClicks[1]);
            swipe(row, 0, -160, false);
            final long duplicateStart = SystemClock.uptimeMillis();
            dispatchPointers(row, duplicateStart, 0, MotionEvent.ACTION_UP, 1, 160);
            assertEquals("duplicate UP reopened a completed gesture", 1, opensAndClicks[0]);
            swipe(row, 0, -160, false);
            assertEquals(2, opensAndClicks[0]);
            assertEquals(1, opensAndClicks[1]);
        });
    }

    private Integer artworkColor() {
        final int id = context.getResources().getIdentifier("current_track_thumbnail", "id",
                context.getPackageName());
        assertTrue("current track artwork is missing", id != 0);
        final ImageView image = active(PlayQueueActivity.class).findViewById(id);
        assertNotNull(image);
        Drawable drawable = image.getDrawable();
        while (drawable instanceof CrossfadeDrawable) {
            final CrossfadeDrawable transition = (CrossfadeDrawable) drawable;
            if (transition.isRunning()) {
                return null;
            }
            drawable = transition.getEnd();
        }
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof BitmapDrawable) {
            // Coil may decode hardware bitmaps, which cannot be drawn on a software Canvas.
            final Bitmap pixels = ((BitmapDrawable) drawable).getBitmap()
                    .copy(Bitmap.Config.ARGB_8888, false);
            assertNotNull(pixels);
            try {
                return pixels.getPixel(pixels.getWidth() / 2, pixels.getHeight() / 2);
            } finally {
                pixels.recycle();
            }
        }
        final Bitmap sample = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        final Rect previousBounds = new Rect(drawable.getBounds());
        drawable.setBounds(0, 0, 16, 16);
        drawable.draw(new Canvas(sample));
        drawable.setBounds(previousBounds);
        final int color = sample.getPixel(8, 8);
        sample.recycle();
        return color;
    }

    private static boolean containsImage(final View view) {
        if (view instanceof ImageView && !(view instanceof android.widget.ImageButton)) {
            return true;
        }
        if (view instanceof android.view.ViewGroup) {
            final android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsImage(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void swipe(final View view, final float dx, final float dy,
                              final boolean cancel) {
        final long start = SystemClock.uptimeMillis();
        final float density = view.getResources().getDisplayMetrics().density;
        final float x = view.getWidth() / 2f;
        final float y = view.getHeight() / 2f;
        for (int i = 0; i <= 8; i++) {
            final int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 8
                    ? (cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP)
                    : MotionEvent.ACTION_MOVE;
            final MotionEvent event = MotionEvent.obtain(start, start + i * 30L, action,
                    x + dx * density * i / 8f, y + dy * density * i / 8f, 0);
            view.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    private static void twoFingerSwipe(final View view, final boolean cancel) {
        final long start = SystemClock.uptimeMillis();
        final int secondPointer = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        dispatchPointers(view, start, 0, MotionEvent.ACTION_DOWN, 1, 0);
        dispatchPointers(view, start, 20,
                MotionEvent.ACTION_POINTER_DOWN | secondPointer, 2, 0);
        dispatchPointers(view, start, 40, MotionEvent.ACTION_MOVE, 2, 160);
        dispatchPointers(view, start, 60, cancel ? MotionEvent.ACTION_CANCEL
                : MotionEvent.ACTION_POINTER_UP | secondPointer, 2, 160);
        // Releasing the remaining finger must not reactivate the canceled gesture.
        dispatchPointers(view, start, 80, MotionEvent.ACTION_UP, 1, 160);
    }

    private static void dispatchPointers(final View view, final long start, final long elapsed,
                                         final int action, final int count, final float upward) {
        final float density = view.getResources().getDisplayMetrics().density;
        final MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        final MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = view.getWidth() / 2f + i * 24 * density;
            coordinates[i].y = view.getHeight() / 2f - upward * density;
            coordinates[i].pressure = 1;
            coordinates[i].size = 1;
        }
        final MotionEvent event = MotionEvent.obtain(start, start + elapsed, action, count,
                properties, coordinates, 0, 0, 1, 1, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void captureScreen(final String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(800);
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

    private File fixture(final String suffix) throws Exception {
        final File file = File.createTempFile("mini-player-", suffix, context.getCacheDir());
        fixtures.add(file);
        return file;
    }

    private static <T extends Activity> T active(final Class<T> type) {
        for (final Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (type.isInstance(activity)) {
                return type.cast(activity);
            }
        }
        return null;
    }

    private static void await(final Check check) throws Exception {
        final long deadline = SystemClock.uptimeMillis() + 10000;
        final boolean[] matched = new boolean[1];
        do {
            onMain(() -> matched[0] = check.matches());
            if (matched[0]) {
                return;
            }
            Thread.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("UI condition timed out", matched[0]);
    }

    private static void onMain(final Action action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                action.run();
            } catch (final Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private interface Action {
        void run() throws Exception;
    }

    private interface Check {
        boolean matches() throws Exception;
    }
}
