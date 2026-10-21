package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;

import org.junit.Test;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.offline.OfflineMediaTag;
import org.schabi.newpipe.player.playqueue.OfflinePlayQueue;
import org.schabi.newpipe.player.ui.PlayerUiList;
import org.schabi.newpipe.util.NavigationHelper;

import java.lang.reflect.Field;
import java.util.List;

/** Local renderer switches must preserve the queue/recovery and never invoke a remote resolver. */
public class OfflineVideoRendererTest {
    @Test
    public void changingLocalPlayerTypeNeverReloadsAfterRecoveryWasConsumed() throws Exception {
        final Player player = mock(Player.class, CALLS_REAL_METHODS);
        final OfflinePlayQueue queue = new OfflinePlayQueue(List.of(new StreamInfoItem(0,
                "backtube-offline:video", "Generated video", StreamType.VIDEO_STREAM)), 0);
        set(player, "playQueue", queue);
        set(player, "UIs", mock(PlayerUiList.class));
        doNothing().when(player).reloadPlayQueueManager();
        doNothing().when(player).useVideoAndSubtitles(true);
        doNothing().when(player).useVideoAndSubtitles(false);
        org.mockito.Mockito.doReturn(true).when(player).isScreenOn();
        try (var navigation = mockStatic(NavigationHelper.class)) {
            set(player, "playerType", PlayerType.MAIN);
            player.handleIntentPost(PlayerType.AUDIO);
            set(player, "playerType", PlayerType.AUDIO);
            player.handleIntentPost(PlayerType.MAIN);
            verify(player, never()).reloadPlayQueueManager();
            verify(player).useVideoAndSubtitles(true);
            verify(player).useVideoAndSubtitles(false);
        }
    }

    @Test
    public void videoAndListeningSwitchOnlyRenderersAndPreservePendingRecovery() throws Exception {
        final Player player = mock(Player.class, CALLS_REAL_METHODS);
        final OfflinePlayQueue queue = new OfflinePlayQueue(List.of(new StreamInfoItem(0,
                "backtube-offline:video", "Generated video", StreamType.VIDEO_STREAM)), 0);
        queue.setRecovery(0, 1200);
        set(player, "playQueue", queue);
        set(player, "currentMetadata", new OfflineMediaTag(queue.getItem()));
        final DefaultTrackSelector selector = mock(DefaultTrackSelector.class);
        final DefaultTrackSelector.Parameters.Builder builder =
                mock(DefaultTrackSelector.Parameters.Builder.class, RETURNS_SELF);
        when(selector.buildUponParameters()).thenReturn(builder);
        set(player, "trackSelector", selector);

        player.useVideoAndSubtitles(true);
        verify(builder).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false);
        verify(builder).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
        set(player, "listeningMode", true);
        player.useVideoAndSubtitles(true);
        verify(builder).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true);
        verify(builder).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        verify(player, never()).reloadPlayQueueManager();
        verify(player, never()).setRecovery();
        assertEquals(1200, queue.getItem().getRecoveryPosition());
    }

    private static void set(final Player player, final String name, final Object value)
            throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }
}
