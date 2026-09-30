package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.util.List;

public class AutoQueueTest {
    @Test
    public void neverRecommendsTheCurrentVideoEvenOutsideTheQueue() {
        final StreamInfo info = info("current");
        info.setRelatedItems(List.of(item("current"), item("next")));
        assertEquals("next", PlayerHelper.autoQueueOf(info, List.of()).getItem().getUrl());
    }

    @Test
    public void preservesRecommendationOrderAndExcludesQueuedVideos() {
        final StreamInfo info = info("current");
        info.setRelatedItems(List.of(item("seen"), item("first"), item("second")));
        final SinglePlayQueue queue = new SinglePlayQueue(item("seen"));
        for (int attempt = 0; attempt < 32; attempt++) {
            assertEquals("first", PlayerHelper.autoQueueOf(info,
                    queue.getStreams()).getItem().getUrl());
        }
    }

    @Test
    public void noCandidateDoesNotCreateARepeatLoop() {
        final StreamInfo info = info("current");
        info.setRelatedItems(List.of(item("current")));
        assertNull(PlayerHelper.autoQueueOf(info, new SinglePlayQueue(info).getStreams()));
        info.setRelatedItems(List.of());
        assertNull(PlayerHelper.autoQueueOf(info, List.of()));
    }

    @Test
    public void skipsCandidatesRejectedByTheRecommendationFilter() {
        final StreamInfo info = info("current");
        info.setRelatedItems(List.of(item("excluded"), item("allowed")));

        assertEquals("allowed", PlayerHelper.autoQueueOf(info, List.of(),
                item -> !item.getUrl().equals("excluded")).getItem().getUrl());
        assertNull(PlayerHelper.autoQueueOf(info, List.of(), item -> false));
    }

    private static StreamInfo info(final String url) {
        return new StreamInfo(0, url, url, StreamType.AUDIO_STREAM, url, "", 0);
    }

    private static StreamInfoItem item(final String url) {
        return new StreamInfoItem(0, url, url, StreamType.AUDIO_STREAM);
    }
}
