package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import org.junit.Test;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class RecommendationExclusionsTest {
    @Test
    public void excludesVideoAndAllowsItAgain() {
        final RecommendationExclusions exclusions = new RecommendationExclusions(preferences());
        final PlayQueueItem item = item(1, "video", "channel");

        assertTrue(exclusions.excludeVideo(item));
        assertTrue(exclusions.excludes(1, "video", "other-channel"));
        assertFalse(exclusions.excludeVideo(item));

        exclusions.allowVideo(item);
        assertFalse(exclusions.excludes(1, "video", "other-channel"));
    }

    @Test
    public void excludesChannelButIgnoresMissingChannelUrl() {
        final RecommendationExclusions exclusions = new RecommendationExclusions(preferences());
        final PlayQueueItem channel = item(1, "video", "channel");

        assertTrue(exclusions.excludeChannel(channel));
        assertTrue(exclusions.excludes(1, "other-video", "channel"));
        assertFalse(exclusions.excludes(1, "other-video", null));
        assertFalse(exclusions.excludeChannel(item(1, "other-video", " ")));
    }

    @Test
    public void clearRemovesBothKindsOfExclusion() {
        final RecommendationExclusions exclusions = new RecommendationExclusions(preferences());
        assertTrue(exclusions.excludeVideo(item(1, "video", "channel")));
        assertTrue(exclusions.excludeChannel(item(1, "other-video", "other-channel")));
        assertTrue(exclusions.hasExclusions());

        exclusions.clear();

        assertFalse(exclusions.hasExclusions());
        assertFalse(exclusions.excludes(1, "video", "channel"));
        assertFalse(exclusions.excludes(1, "other-video", "other-channel"));
    }

    private static PlayQueueItem item(final int serviceId, final String url,
                                      final String uploaderUrl) {
        final StreamInfo info = new StreamInfo(serviceId, url, url, StreamType.AUDIO_STREAM,
                url, "", 0);
        info.setUploaderUrl(uploaderUrl);
        return new SinglePlayQueue(info).getItem();
    }

    @SuppressWarnings("unchecked")
    private static SharedPreferences preferences() {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(preferences.getStringSet(anyString(), any())).thenAnswer(invocation -> {
            final Object value = values.get(invocation.getArgument(0));
            return value == null ? invocation.getArgument(1) : value;
        });
        when(editor.putStringSet(anyString(), any())).thenAnswer(invocation -> {
            values.put(invocation.getArgument(0),
                    new HashSet<>((Set<String>) invocation.getArgument(1)));
            return editor;
        });
        when(editor.remove(anyString())).thenAnswer(invocation -> {
            values.remove(invocation.getArgument(0));
            return editor;
        });
        doAnswer(invocation -> {
            values.clear();
            return null;
        }).when(editor).clear();
        return preferences;
    }
}
