package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.ui.MainPlayerUi;
import org.schabi.newpipe.player.ui.PlayerUiList;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilderFactory;

public class PlaybackControlsTest {
    @Test
    public void initialOverlayDescriptionMatchesPlayIconInEveryDetailLayout() throws Exception {
        final String android = "http://schemas.android.com/apk/res/android";
        for (final String layout : new String[]{"layout", "layout-large-land"}) {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            final NodeList nodes = factory.newDocumentBuilder().parse(new File("src/main/res/"
                    + layout + "/fragment_video_detail.xml")).getElementsByTagName("ImageButton");
            boolean found = false;
            for (int index = 0; index < nodes.getLength(); index++) {
                final Element button = (Element) nodes.item(index);
                if ("@+id/overlay_play_pause_button".equals(button.getAttributeNS(android, "id"))) {
                    found = true;
                    assertEquals("@drawable/ic_play_arrow", button.getAttributeNS(android, "src"));
                    assertEquals("@string/play", button.getAttributeNS(android,
                            "contentDescription"));
                }
            }
            assertTrue(layout + " has no overlay play/pause button", found);
        }
    }

    @Test
    public void overlayDescriptionFollowsNextActionAcrossPlaybackChanges() throws Exception {
        final VideoDetailFragment fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        final FragmentVideoDetailBinding binding = mock(FragmentVideoDetailBinding.class);
        final ImageButton button = mock(ImageButton.class);
        set(FragmentVideoDetailBinding.class, binding, "overlayPlayPauseButton", button);
        set(VideoDetailFragment.class, fragment, "binding", binding);
        doReturn("Play").when(fragment).getString(R.string.play);
        doReturn("Pause").when(fragment).getString(R.string.pause);
        final int[] icon = new int[1];
        final CharSequence[] description = new CharSequence[1];
        doAnswer(call -> {
            icon[0] = call.getArgument(0);
            return null;
        }).when(button).setImageResource(org.mockito.ArgumentMatchers.anyInt());
        doAnswer(call -> {
            description[0] = call.getArgument(0);
            return null;
        }).when(button).setContentDescription(org.mockito.ArgumentMatchers.any());
        for (final boolean playing : new boolean[]{false, true, false}) {
            invoke(fragment, "setOverlayPlayPauseImage", new Class<?>[]{boolean.class}, playing);
            assertEquals(playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow, icon[0]);
            assertEquals(playing ? "Pause" : "Play", description[0]);
        }
    }

    @Test
    public void pausedLandscapeDoesNotEnableAutoplayForAnUnsynchronizedQueue() throws Exception {
        final VideoDetailFragment fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        final Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        set(VideoDetailFragment.class, fragment, "player", player);
        set(VideoDetailFragment.class, fragment, "autoPlayEnabled", false);
        set(VideoDetailFragment.class, fragment, "playOnSelection", true);
        when(player.getPlayQueue()).thenReturn(null);
        try (MockedStatic<PlayerHelper> helper = mockStatic(PlayerHelper.class)) {
            invoke(fragment, "checkLandscape", new Class<?>[0]);
        }
        assertFalse("landscape must preserve disabled autoplay",
                (boolean) get(fragment, "autoPlayEnabled"));
        assertTrue((boolean) get(fragment, "playOnSelection"));
    }

    @Test
    public void pausedLandscapeDoesNotResumeOrChangeAutoplayBeforeQueueSynchronization()
            throws Exception {
        assertLandscapePreservesPlayback(false, false);
        assertLandscapePreservesPlayback(false, true);
        assertLandscapePreservesPlayback(true, false);
    }

    @Test
    public void playingLandscapeStillDelegatesFullscreenPolicyWithoutPlaybackMutation()
            throws Exception {
        assertLandscapePreservesPlayback(true, true);
    }

    private static void assertLandscapePreservesPlayback(final boolean sameQueue,
                                                         final boolean playing) throws Exception {
        final VideoDetailFragment fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        final Player player = mock(Player.class);
        final PlayerUiList uis = mock(PlayerUiList.class);
        final MainPlayerUi ui = mock(MainPlayerUi.class);
        final AppCompatActivity activity = mock(AppCompatActivity.class);
        final PlayQueue queue = mock(PlayQueue.class);
        set(VideoDetailFragment.class, fragment, "player", player);
        set(VideoDetailFragment.class, fragment, "playQueue", queue);
        set(VideoDetailFragment.class, fragment, "autoPlayEnabled", false);
        set(VideoDetailFragment.class, fragment, "playOnSelection", true);
        set(BaseFragment.class, fragment, "activity", activity);
        when(player.UIs()).thenReturn(uis);
        when(uis.get(MainPlayerUi.class)).thenReturn(Optional.of(ui));
        when(player.isPlaying()).thenReturn(playing);
        when(player.getPlayQueue()).thenReturn(sameQueue ? queue : null);
        try (MockedStatic<PlayerHelper> helper = mockStatic(PlayerHelper.class)) {
            helper.when(() -> PlayerHelper.globalScreenOrientationLocked(activity))
                    .thenReturn(true);
            invoke(fragment, "checkLandscape", new Class<?>[0]);
        }
        verify(ui).checkLandscape();
        verify(player, never()).play();
        verify(player, never()).pause();
        verify(player, never()).seekToDefault();
        assertFalse((boolean) get(fragment, "autoPlayEnabled"));
        assertTrue((boolean) get(fragment, "playOnSelection"));
    }

    private static Object get(final Object target, final String name) throws Exception {
        final Field field = VideoDetailFragment.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(final Object target, final String name, final Class<?>[] types,
                               final Object... arguments) throws Exception {
        final Method method = VideoDetailFragment.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, arguments);
    }

    private static void set(final Class<?> owner, final Object target, final String name,
                            final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
