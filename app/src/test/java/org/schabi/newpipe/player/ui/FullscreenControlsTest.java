package org.schabi.newpipe.player.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.view.ViewCompat;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.event.PlayerServiceEventListener;
import org.schabi.newpipe.player.gesture.BasePlayerGestureListener;
import org.schabi.newpipe.player.helper.PlayerHelper;

import java.lang.reflect.Field;
import java.util.Optional;

/** Real listener routing and fullscreen state; Android rendering is a mocked boundary. */
public class FullscreenControlsTest {
    @Test
    public void pausedFullscreenClickEntersImmediatelyWhileOrientationChangeIsPending()
            throws Exception {
        assertClick(false, false, false, false, false);
    }

    @Test
    public void pausedLandscapeInlineClickEntersWithoutRequestingPortrait() throws Exception {
        assertClick(false, false, true, false, false);
    }

    @Test
    public void completedFullscreenClickEntersWithoutStartingPlayback() throws Exception {
        assertClick(false, false, false, false, true);
    }

    @Test
    public void fullscreenExitClickDoesNotReenterWhileOrientationChangeIsPending()
            throws Exception {
        assertClick(true, false, true, false, false);
    }

    @Test
    public void tabletOrTvCallbackThatAlreadyEntersFullscreenDoesNotToggleTwice()
            throws Exception {
        assertClick(false, false, false, true, false);
    }

    @Test
    public void tabletOrTvCallbackThatExitsFullscreenDoesNotReenter() throws Exception {
        assertClick(true, false, true, true, false);
    }

    @Test
    public void verticalPortraitClickKeepsDirectFullscreenRouting() throws Exception {
        assertClick(false, true, false, false, false);
    }

    @Test
    public void passiveLandscapeKeepsPausedAndCompletedPlayersInline() throws Exception {
        final MainPlayerUi ui = mock(MainPlayerUi.class, CALLS_REAL_METHODS);
        final Player player = mock(Player.class);
        set(PlayerUi.class, ui, "player", player);
        doReturn(true).when(ui).isLandscape();
        for (final int state : new int[]{Player.STATE_PAUSED, Player.STATE_COMPLETED}) {
            when(player.getCurrentState()).thenReturn(state);
            ui.checkLandscape();
            assertFalse(ui.isFullscreen());
        }
        verify(ui, never()).toggleFullscreen();
    }

    private static void assertClick(final boolean wasFullscreen, final boolean vertical,
                                    final boolean landscape, final boolean callbackToggles,
                                    final boolean completed) throws Exception {
        final MainPlayerUi ui = mock(MainPlayerUi.class, CALLS_REAL_METHODS);
        final Player player = mock(Player.class);
        final Context context = mock(Context.class);
        final PlayerBinding binding = mock(PlayerBinding.class);
        final PlayerServiceEventListener listener = mock(PlayerServiceEventListener.class);
        for (final Field field : PlayerBinding.class.getDeclaredFields()) {
            if (View.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                field.set(binding, mock(field.getType()));
            }
        }
        when(binding.getRoot()).thenReturn(mock(RelativeLayout.class));
        when(context.getContentResolver()).thenReturn(mock(ContentResolver.class));
        when(player.getFragmentListener()).thenReturn(Optional.of(listener));
        when(player.getCurrentState()).thenReturn(completed ? Player.STATE_COMPLETED
                : Player.STATE_PAUSED);
        set(PlayerUi.class, ui, "player", player);
        set(PlayerUi.class, ui, "context", context);
        set(VideoPlayerUi.class, ui, "binding", binding);
        set(MainPlayerUi.class, ui, "isFullscreen", wasFullscreen);
        set(MainPlayerUi.class, ui, "isVerticalVideo", vertical);
        doReturn(landscape).when(ui).isLandscape();
        doNothing().when(ui).hideControls(0, 0);
        doReturn(mock(BasePlayerGestureListener.class)).when(ui).buildGestureListener();
        // Isolate the surrounding controls animation; run the production action unchanged.
        doAnswer(call -> (View.OnClickListener) view -> ((Runnable) call.getArgument(0)).run())
                .when(ui).makeOnClickListener(any());
        if (callbackToggles) {
            doAnswer(call -> {
                ui.toggleFullscreen();
                return null;
            }).when(listener).onScreenRotationButtonClicked();
        }
        try (MockedStatic<ViewCompat> insets = mockStatic(ViewCompat.class);
                MockedStatic<PlayerHelper> helper = mockStatic(PlayerHelper.class);
                MockedStatic<Looper> loopers = mockStatic(Looper.class);
                MockedStatic<Settings.System> settings = mockStatic(Settings.System.class);
                MockedStatic<Log> logging = mockStatic(Log.class);
                MockedConstruction<Handler> handlers = mockConstruction(Handler.class);
                MockedConstruction<GestureDetector> gestures = mockConstruction(
                        GestureDetector.class);
                MockedConstruction<?> observers = mockConstruction(
                        Class.forName("org.schabi.newpipe.player.ui.MainPlayerUi$1"));
                MockedStatic<AppCompatResources> resources = mockStatic(AppCompatResources.class)) {
            helper.when(() -> PlayerHelper.globalScreenOrientationLocked(context)).thenReturn(true);
            ui.initListeners();
            final ArgumentCaptor<View.OnClickListener> click = ArgumentCaptor
                    .forClass(View.OnClickListener.class);
            verify(binding.screenRotationButton).setOnClickListener(click.capture());
            click.getValue().onClick(binding.screenRotationButton);
        }
        if (wasFullscreen && !callbackToggles) {
            assertTrue(ui.isFullscreen()); // recreation will finish the requested exit
        } else {
            assertTrue("explicit paused entry must actually enter fullscreen",
                    ui.isFullscreen() != wasFullscreen);
        }
        final boolean directEntry = !wasFullscreen && !vertical && landscape;
        verify(listener, times(vertical && !landscape || directEntry ? 0 : 1))
                .onScreenRotationButtonClicked();
        verify(listener, times(wasFullscreen && !callbackToggles ? 0 : 1))
                .onFullscreenStateChanged(!wasFullscreen);
        verify(player, never()).play();
        verify(player, never()).pause();
        verify(player, never()).seekToDefault();
    }

    private static void set(final Class<?> owner, final Object target, final String name,
                            final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
