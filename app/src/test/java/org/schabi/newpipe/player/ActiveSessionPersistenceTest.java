package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;
import android.os.SystemClock;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Timeline;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.helper.LastPlaybackSessionStore;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Real Player persistence and JSON store with Android preferences/media boundaries substituted. */
public class ActiveSessionPersistenceTest {
    @Test
    public void latestActivePositionWinsAfterPauseAndContinuedPlayback() throws Exception {
        final Map<String, String> persisted = new HashMap<>();
        persisted.put("unrelated-history-fixture", "retained");
        final SharedPreferences preferences = preferences(persisted);
        final LastPlaybackSessionStore store = new LastPlaybackSessionStore(preferences);
        final Player player = mock(Player.class);
        final ExoPlayer exo = mock(ExoPlayer.class);
        final PlayQueue queue = new SinglePlayQueue(List.of(item("first"), item("active")), 1);
        final MediaItemTag tag = mock(MediaItemTag.class);
        when(tag.getServiceId()).thenReturn(0);
        when(tag.getStreamUrl()).thenReturn("https://example.invalid/active");
        when(player.getCurrentStreamInfo()).thenReturn(Optional.empty());
        doCallRealMethod().when(player).saveStreamProgressState();
        doCallRealMethod().when(player).saveLastSession();
        when(player.getRepeatMode()).thenReturn(2);
        when(exo.getCurrentMediaItemIndex()).thenReturn(1);
        when(exo.getCurrentPosition()).thenReturn(12_000L);
        when(exo.getContentPosition()).thenReturn(12_000L);
        set(player, "playQueue", queue);
        set(player, "simpleExoPlayer", exo);
        set(player, "currentMetadata", tag);
        set(player, "lastSessionStore", store);
        player.saveStreamProgressState();
        // Same active item continued playing after a pause/seek saved its old queue recovery.
        when(exo.getContentPosition()).thenReturn(23_000L);
        try (MockedStatic<MediaItemTag> tags = mockStatic(MediaItemTag.class);
             MockedStatic<SystemClock> clock = mockStatic(SystemClock.class)) {
            tags.when(() -> MediaItemTag.from(any())).thenReturn(Optional.of(tag));
            player.saveLastSession();
        }
        final LastPlaybackSessionStore.Snapshot snapshot =
                new LastPlaybackSessionStore(preferences).load();
        assertNotNull(snapshot);
        assertEquals(2, snapshot.getQueue().size());
        assertEquals(1, snapshot.getQueue().getIndex());
        assertEquals("https://example.invalid/active", snapshot.getQueue().getItem().getUrl());
        assertEquals(23_000L, snapshot.getQueue().getItem().getRecoveryPosition());
        assertEquals(2, snapshot.getRepeatMode());
        assertEquals("retained", persisted.get("unrelated-history-fixture"));
        // Consume the persisted queue through the real player/source synchronization boundary.
        final Player restoredPlayer = mock(Player.class);
        doCallRealMethod().when(restoredPlayer).onPlaybackSynchronize(any(),
                org.mockito.ArgumentMatchers.anyBoolean());
        final Timeline timeline = mock(Timeline.class);
        when(timeline.getWindowCount()).thenReturn(2);
        when(exo.getCurrentTimeline()).thenReturn(timeline);
        when(exo.getCurrentMediaItemIndex()).thenReturn(0);
        final long[] restoredPosition = {-1};
        doAnswer(call -> {
            restoredPosition[0] = call.getArgument(1);
            return null;
        }).when(exo).seekTo(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        set(restoredPlayer, "playQueue", snapshot.getQueue());
        set(restoredPlayer, "simpleExoPlayer", exo);
        try (MockedStatic<android.util.Log> logging = mockStatic(android.util.Log.class)) {
            restoredPlayer.onPlaybackSynchronize(snapshot.getQueue().getItem(), true);
        }
        assertEquals(23_000L, restoredPosition[0]);
        assertEquals(org.schabi.newpipe.player.playqueue.PlayQueueItem.RECOVERY_UNSET,
                snapshot.getQueue().getItem().getRecoveryPosition());
    }

    @Test
    public void missingSessionLeavesUnrelatedStoredDataUntouched() {
        final Map<String, String> persisted = new HashMap<>();
        persisted.put("unrelated-history-fixture", "retained");
        assertNull(new LastPlaybackSessionStore(preferences(persisted)).load());
        assertEquals(Map.of("unrelated-history-fixture", "retained"), persisted);
    }

    @Test
    public void malformedSessionClearsOnlyItsOwnPreference() {
        final Map<String, String> persisted = new HashMap<>();
        persisted.put("last_playback_session", "not-json");
        persisted.put("unrelated-history-fixture", "retained");
        assertNull(new LastPlaybackSessionStore(preferences(persisted)).load());
        assertEquals(Map.of("unrelated-history-fixture", "retained"), persisted);
    }

    @Test
    public void pendingRecoverySurvivesUntilTheSourceAndIndexMatch() throws Exception {
        for (final boolean wrongIndex : new boolean[]{false, true}) {
            final Map<String, String> persisted = new HashMap<>();
            final LastPlaybackSessionStore store = new LastPlaybackSessionStore(
                    preferences(persisted));
            final Player player = mock(Player.class);
            doCallRealMethod().when(player).saveLastSession();
            final PlayQueue queue = new SinglePlayQueue(List.of(item("first"), item("active")), 1);
            queue.setRecovery(1, 29_000);
            final ExoPlayer exo = mock(ExoPlayer.class);
            when(exo.getCurrentMediaItemIndex()).thenReturn(wrongIndex ? 0 : 1);
            when(exo.getContentPosition()).thenReturn(3_000L);
            final MediaItemTag tag = mock(MediaItemTag.class);
            when(tag.getServiceId()).thenReturn(0);
            when(tag.getStreamUrl()).thenReturn(wrongIndex
                    ? "https://example.invalid/active" : "https://example.invalid/other");
            set(player, "playQueue", queue);
            set(player, "simpleExoPlayer", exo);
            set(player, "lastSessionStore", store);
            try (MockedStatic<MediaItemTag> tags = mockStatic(MediaItemTag.class);
                 MockedStatic<SystemClock> clock = mockStatic(SystemClock.class)) {
                tags.when(() -> MediaItemTag.from(any())).thenReturn(Optional.of(tag));
                player.saveLastSession();
            }
            assertEquals(29_000, store.load().getQueue().getItem().getRecoveryPosition());
        }
    }

    static SharedPreferences preferences(final Map<String, String> values) {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.getString(anyString(), nullable(String.class)))
                .thenAnswer(call -> values.getOrDefault(call.getArgument(0), call.getArgument(1)));
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            return editor;
        });
        when(editor.remove(anyString())).thenAnswer(call -> {
            values.remove(call.getArgument(0));
            return editor;
        });
        return preferences;
    }

    private static StreamInfoItem item(final String id) {
        final StreamInfoItem item = new StreamInfoItem(0, "https://example.invalid/" + id,
                "Generated " + id, StreamType.AUDIO_STREAM);
        item.setDuration(300);
        return item;
    }

    private static void set(final Player player, final String name, final Object value)
            throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }
}
