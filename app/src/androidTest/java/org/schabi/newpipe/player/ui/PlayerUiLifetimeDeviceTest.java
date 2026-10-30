package org.schabi.newpipe.player.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.exoplayer2.video.VideoSize;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerService;
import org.schabi.newpipe.player.event.PlayerServiceEventListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Real Android UI callbacks over a generated, nonplaying test service. */
@RunWith(AndroidJUnit4.class)
public class PlayerUiLifetimeDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final CountDownLatch connected = new CountDownLatch(1);
    private PlayerService service;
    private Player player;
    private PlayerBinding binding;
    private MainPlayerUi oldUi;
    private MainPlayerUi replacementUi;
    private boolean bound;
    private boolean oldDestroyed;
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
        ContextCompat.startForegroundService(context, new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue(connected.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 100 && (service == null || service.getPlayer() == null); i++) {
            Thread.sleep(50);
        }
        assertNotNull(service);
        player = service.getPlayer();
        assertNotNull(player);
        final Context themed = new ContextThemeWrapper(context, R.style.LightTheme);
        onMain(() -> {
            binding = PlayerBinding.inflate(LayoutInflater.from(themed));
            oldUi = new MainPlayerUi(player, binding);
        });
    }

    @After
    public void tearDown() throws Exception {
        onMain(() -> {
            if (replacementUi != null) {
                replacementUi.destroy();
            }
            if (oldUi != null && !oldDestroyed) {
                oldUi.destroy();
            }
            if (service != null) {
                service.destroyPlayerAndStopService();
            }
        });
        if (bound) {
            context.unbindService(connection);
        }
    }

    @Test
    public void zeroVideoSizeRetainsLastVerticalDecision() throws Exception {
        final Field vertical = MainPlayerUi.class.getDeclaredField("isVerticalVideo");
        vertical.setAccessible(true);
        vertical.set(oldUi, true);

        onMain(() -> oldUi.onVideoSizeChanged(new VideoSize(0, 0)));

        assertTrue(oldUi.isVerticalVideo());
    }

    @Test
    public void validVideoSizeStillUpdatesOrientationAndAspect() throws Exception {
        onMain(() -> oldUi.onVideoSizeChanged(new VideoSize(1920, 1080)));

        assertEquals(false, oldUi.isVerticalVideo());
        final Field ratio = binding.surfaceView.getClass().getDeclaredField("videoAspectRatio");
        ratio.setAccessible(true);
        assertEquals(1920f / 1080f, ratio.getFloat(binding.surfaceView), 0.001f);
    }

    @Test
    public void oldDelayedHideCannotChangeSharedControlsAfterSwitch() throws Exception {
        onMain(() -> {
            oldUi.hideControls(0, 150);
            oldUi.destroy();
            oldDestroyed = true;
            replacementUi = new MainPlayerUi(player, binding);
            replacementUi.showControls(0);
        });

        Thread.sleep(250);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(View.VISIBLE, binding.playbackControlRoot.getVisibility());
    }

    @Test
    public void oldClickAndDismissCallbacksCannotActOnReplacement() throws Exception {
        final AtomicInteger clicks = new AtomicInteger();
        final AtomicReference<View.OnClickListener> stale = new AtomicReference<>();
        onMain(() -> {
            stale.set(oldUi.makeOnClickListener(clicks::incrementAndGet));
            oldUi.destroy();
            oldDestroyed = true;
            replacementUi = new MainPlayerUi(player, binding);
            oldUi.isSomePopupMenuVisible = true;
            stale.get().onClick(binding.playPauseButton);
            oldUi.onDismiss(null);
        });

        assertEquals(0, clicks.get());
        assertTrue(oldUi.isSomePopupMenuVisible());

        onMain(() -> replacementUi.makeOnClickListener(clicks::incrementAndGet)
                .onClick(binding.playPauseButton));
        assertEquals(1, clicks.get());
    }

    private static void onMain(final Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    @Test
    public void videoSurfaceSwipesUseExistingFullscreenWithoutChangingPlayback() {
        onMain(() -> {
            try {
                final Method init = Player.class.getDeclaredMethod("initPlayer", boolean.class);
                init.setAccessible(true);
                init.invoke(player, false);
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            final AtomicInteger fullscreenEvents = new AtomicInteger();
            final PlayerServiceEventListener listener = (PlayerServiceEventListener)
                    Proxy.newProxyInstance(PlayerServiceEventListener.class.getClassLoader(),
                            new Class<?>[]{PlayerServiceEventListener.class},
                            (proxy, method, args) -> {
                                if ("onFullscreenStateChanged".equals(method.getName())) {
                                    fullscreenEvents.incrementAndGet();
                                }
                                return null;
                            });
            player.setFragmentListener(listener);
            try {
                final long position = player.getExoPlayer().getCurrentPosition();
                final boolean playWhenReady = player.getExoPlayer().getPlayWhenReady();
                final long time = SystemClock.uptimeMillis();
                dispatchSingle(time, time, MotionEvent.ACTION_DOWN, 100f, 600f);
                dispatchSingle(time, time + 60, MotionEvent.ACTION_MOVE, 102f, 320f);
                dispatchSingle(time, time + 80, MotionEvent.ACTION_UP, 102f, 320f);
                assertTrue(oldUi.isFullscreen());
                assertEquals(1, fullscreenEvents.get());

                final long secondTime = time + 1000;
                final int pointerIndex = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                dispatchSingle(secondTime, secondTime, MotionEvent.ACTION_DOWN, 80f, 100f);
                dispatchMulti(secondTime, secondTime + 50,
                        MotionEvent.ACTION_POINTER_DOWN | pointerIndex,
                        80f, 100f, 180f, 100f);
                dispatchMulti(secondTime, secondTime + 100, MotionEvent.ACTION_MOVE,
                        82f, 400f, 182f, 400f);
                dispatchMulti(secondTime, secondTime + 120,
                        MotionEvent.ACTION_POINTER_UP | pointerIndex,
                        82f, 400f, 182f, 400f);
                dispatchSingle(secondTime, secondTime + 140, MotionEvent.ACTION_UP, 82f, 400f);
                assertFalse(oldUi.isFullscreen());
                assertEquals(2, fullscreenEvents.get());

                assertEquals(position, player.getExoPlayer().getCurrentPosition());
                assertEquals(playWhenReady, player.getExoPlayer().getPlayWhenReady());

                final long doubleTapTime = secondTime + 1000;
                dispatchSingle(doubleTapTime, doubleTapTime,
                        MotionEvent.ACTION_DOWN, 100f, 600f);
                dispatchSingle(doubleTapTime, doubleTapTime + 40,
                        MotionEvent.ACTION_UP, 100f, 600f);
                dispatchSingle(doubleTapTime, doubleTapTime + 120,
                        MotionEvent.ACTION_DOWN, 100f, 600f);
                dispatchSingle(doubleTapTime, doubleTapTime + 170,
                        MotionEvent.ACTION_MOVE, 100f, 300f);
                dispatchSingle(doubleTapTime, doubleTapTime + 190,
                        MotionEvent.ACTION_UP, 100f, 300f);
                assertFalse(oldUi.isFullscreen());
                assertEquals(2, fullscreenEvents.get());

                final long middleDoubleTapTime = doubleTapTime + 1000;
                dispatchSingle(middleDoubleTapTime, middleDoubleTapTime,
                        MotionEvent.ACTION_DOWN, 0f, 600f);
                dispatchSingle(middleDoubleTapTime, middleDoubleTapTime + 40,
                        MotionEvent.ACTION_UP, 0f, 600f);
                dispatchSingle(middleDoubleTapTime, middleDoubleTapTime + 120,
                        MotionEvent.ACTION_DOWN, 0f, 600f);
                dispatchSingle(middleDoubleTapTime, middleDoubleTapTime + 170,
                        MotionEvent.ACTION_MOVE, 0f, 300f);
                dispatchSingle(middleDoubleTapTime, middleDoubleTapTime + 190,
                        MotionEvent.ACTION_UP, 0f, 300f);
                assertFalse(oldUi.isFullscreen());
                assertEquals(2, fullscreenEvents.get());
            } finally {
                player.removeFragmentListener(listener);
            }
        });
    }

    private void dispatchSingle(final long downTime, final long eventTime,
                                final int action, final float x, final float y) {
        final MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        binding.getRoot().dispatchTouchEvent(event);
        event.recycle();
    }

    private void dispatchMulti(final long downTime, final long eventTime, final int action,
                               final float x0, final float y0, final float x1, final float y1) {
        final MotionEvent.PointerProperties first = new MotionEvent.PointerProperties();
        first.id = 0;
        first.toolType = MotionEvent.TOOL_TYPE_FINGER;
        final MotionEvent.PointerProperties second = new MotionEvent.PointerProperties();
        second.id = 1;
        second.toolType = MotionEvent.TOOL_TYPE_FINGER;
        final MotionEvent.PointerCoords firstPosition = new MotionEvent.PointerCoords();
        firstPosition.x = x0;
        firstPosition.y = y0;
        firstPosition.pressure = 1f;
        firstPosition.size = 1f;
        final MotionEvent.PointerCoords secondPosition = new MotionEvent.PointerCoords();
        secondPosition.x = x1;
        secondPosition.y = y1;
        secondPosition.pressure = 1f;
        secondPosition.size = 1f;
        final MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 2,
                new MotionEvent.PointerProperties[]{first, second},
                new MotionEvent.PointerCoords[]{firstPosition, secondPosition},
                0, 0, 1f, 1f, 0, 0, 0, 0);
        binding.getRoot().dispatchTouchEvent(event);
        event.recycle();
    }
}
