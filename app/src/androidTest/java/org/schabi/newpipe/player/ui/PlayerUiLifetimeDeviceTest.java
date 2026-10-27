package org.schabi.newpipe.player.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
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

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
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
}
