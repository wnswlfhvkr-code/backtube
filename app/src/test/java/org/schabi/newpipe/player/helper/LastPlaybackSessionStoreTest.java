package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class LastPlaybackSessionStoreTest {
    private final AtomicReference<String> savedJson = new AtomicReference<>();
    private LastPlaybackSessionStore store;
    private boolean wrongPreferenceType;

    @Before
    public void setUp() {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.getString(anyString(), nullable(String.class)))
                .thenAnswer(ignored -> {
                    if (wrongPreferenceType) {
                        throw new ClassCastException("saved value is not a string");
                    }
                    return savedJson.get();
                });
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenAnswer(invocation -> {
            savedJson.set(invocation.getArgument(1));
            return editor;
        });
        when(editor.remove(anyString())).thenAnswer(invocation -> {
            savedJson.set(null);
            return editor;
        });
        doAnswer(ignored -> null).when(editor).apply();
        store = new LastPlaybackSessionStore(preferences);
    }

    @Test
    public void saveAndLoadPreservesLoadedQueueState() {
        final StreamInfoItem first = item(1, "https://example.com/first", "First");
        final StreamInfoItem second = item(2, "https://example.com/second", "Second");
        final PlayQueue queue = new SinglePlayQueue(List.of(first, second), 1);
        queue.getItem(0).setAutoQueued(true);

        store.save(queue, 12_345L, 2);

        final LastPlaybackSessionStore.Snapshot snapshot = store.load();
        final PlayQueue restoredQueue = snapshot.getQueue();
        final PlayQueueItem restoredFirst = restoredQueue.getItem(0);
        final PlayQueueItem restoredSecond = restoredQueue.getItem(1);

        assertEquals(2, restoredQueue.size());
        assertEquals(1, restoredQueue.getIndex());
        assertEquals("https://example.com/first", restoredFirst.getUrl());
        assertEquals("First", restoredFirst.getTitle());
        assertEquals(1, restoredFirst.getServiceId());
        assertEquals(StreamType.AUDIO_STREAM, restoredFirst.getStreamType());
        assertEquals("Uploader First", restoredFirst.getUploader());
        assertEquals("https://example.com/uploader/First", restoredFirst.getUploaderUrl());
        assertEquals(123L, restoredFirst.getDuration());
        assertTrue(restoredFirst.isAutoQueued());
        assertEquals("https://example.com/second", restoredSecond.getUrl());
        assertEquals(12_345L, restoredSecond.getRecoveryPosition());
        assertEquals(2, snapshot.getRepeatMode());
    }

    @Test
    public void loadClearsMalformedAndUnsupportedSnapshots() {
        savedJson.set("not json");

        assertNull(store.load());
        assertNull(savedJson.get());

        wrongPreferenceType = true;

        assertNull(store.load());
        assertNull(savedJson.get());

        wrongPreferenceType = false;
        savedJson.set("{\"version\":2,\"items\":[]}");

        assertNull(store.load());
        assertNull(savedJson.get());
    }

    @Test
    public void saveAndLoadPreservesLiveItemsWithoutUploader() {
        final StreamInfoItem live = item(1, "https://example.com/live", "Live");
        live.setDuration(-1);
        live.setUploaderName(null);
        live.setUploaderUrl(null);

        store.save(new SinglePlayQueue(live), 500L, 0);

        final PlayQueueItem restored = store.load().getQueue().getItem();
        assertEquals("https://example.com/live", restored.getUrl());
        assertEquals(-1L, restored.getDuration());
        assertEquals("", restored.getUploader());
        assertNull(restored.getUploaderUrl());
    }

    @Test
    public void clearRemovesSavedSnapshot() {
        store.save(new SinglePlayQueue(item(1, "https://example.com/one", "One")), 0, 0);

        store.clear();

        assertNull(store.load());
        assertNull(savedJson.get());
    }

    private static StreamInfoItem item(final int serviceId, final String url, final String title) {
        final StreamInfoItem item = new StreamInfoItem(serviceId, url, title,
                StreamType.AUDIO_STREAM);
        item.setUploaderName("Uploader " + title);
        item.setUploaderUrl("https://example.com/uploader/" + title);
        item.setDuration(123);
        return item;
    }
}
