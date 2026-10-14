package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.NavigationHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise the real expansion gate; Android launch and the active player are boundaries. */
public class OfflineControllerNavigationTest {
    @Test
    public void stayingOnSavedControllerCannotBlockLaterOnlineExpansion() throws Exception {
        final PlayQueueActivity activity = mock(PlayQueueActivity.class, CALLS_REAL_METHODS);
        final Player player = mock(Player.class);
        final AtomicReference<PlayQueue> selected = new AtomicReference<>(
                new SinglePlayQueue(List.of(new StreamInfoItem(0, "backtube-offline:owned",
                        "Saved track", StreamType.AUDIO_STREAM)), 0));
        when(player.getPlayQueue()).thenAnswer(invocation -> selected.get());
        final Field activePlayer = PlayQueueActivity.class.getDeclaredField("player");
        activePlayer.setAccessible(true);
        activePlayer.set(activity, player);
        final Method expand = PlayQueueActivity.class.getDeclaredMethod("openPlaybackScreen");
        expand.setAccessible(true);
        final List<String> opened = new ArrayList<>();
        try (MockedStatic<NavigationHelper> navigation = mockStatic(NavigationHelper.class)) {
            navigation.when(() -> NavigationHelper.playOnMainPlayer(
                    any(AppCompatActivity.class), any(PlayQueue.class), eq(true)))
                    .thenAnswer(invocation -> {
                        opened.add(((PlayQueue) invocation.getArgument(1)).getItem().getUrl());
                        return null;
                    });
            expand.invoke(activity);
            expand.invoke(activity);
            selected.set(new SinglePlayQueue(List.of(new StreamInfoItem(0,
                    "https://example.invalid/online", "Online track",
                    StreamType.AUDIO_STREAM)), 0));
            expand.invoke(activity);
            expand.invoke(activity);
            assertEquals(List.of("backtube-offline:owned", "backtube-offline:owned",
                    "https://example.invalid/online"), opened);
        }
    }
}
