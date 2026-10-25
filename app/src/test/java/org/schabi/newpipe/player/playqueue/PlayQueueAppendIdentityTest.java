package org.schabi.newpipe.player.playqueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PlayQueueAppendIdentityTest {
    @Before
    public void setUp() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
    }

    @After
    public void tearDown() {
        RxAndroidPlugins.reset();
    }

    @Test
    public void emptyAppendPreservesPendingRecommendationAndSignalsLoader() {
        final SinglePlayQueue queue = queue(0, "A", "B");
        final PlayQueueItem pending = queue.getItem(1);
        pending.setAutoQueued(true);
        queue.init();
        final var events = queue.getBroadcastReceiver().test();

        queue.append(Collections.emptyList());

        assertEquals(2, queue.size());
        assertSame(pending, queue.getItem(1));
        events.assertValueCount(2);
        assertEquals(PlayQueueEvent.Type.APPEND, events.values().get(1).type());
        queue.dispose();
    }

    @Test
    public void manualAppendNeverReplacesSelectedRecommendation() {
        final SinglePlayQueue queue = queue(1, "A", "B");
        final PlayQueueItem selected = queue.getItem();
        selected.setAutoQueued(true);

        queue.append(List.of(item("C")));

        assertSame(selected, queue.getItem());
        assertEquals(3, queue.size());
        assertEquals("C", queue.getItem(2).getUrl());
    }

    @Test
    public void manualAppendReplacesOnlyPendingRecommendationWithRemoveEvent() {
        final SinglePlayQueue queue = queue(0, "A", "B");
        final PlayQueueItem pending = queue.getItem(1);
        pending.setAutoQueued(true);
        queue.init();
        final var events = queue.getBroadcastReceiver().test();

        queue.append(List.of(item("C")));

        assertEquals(2, queue.size());
        assertEquals("A", queue.getItem().getUrl());
        assertEquals("C", queue.getItem(1).getUrl());
        assertFalse(queue.getStreams().contains(pending));
        events.assertValueCount(3);
        assertEquals(PlayQueueEvent.Type.REMOVE, events.values().get(1).type());
        assertEquals(PlayQueueEvent.Type.APPEND, events.values().get(2).type());
        queue.dispose();
    }

    @Test
    public void removedRecommendationNeverReturnsAfterUnshuffle() {
        final SinglePlayQueue queue = queue(0, "A", "B");
        final PlayQueueItem pending = queue.getItem(1);
        pending.setAutoQueued(true);
        queue.shuffle();

        queue.append(List.of(item("C")));
        queue.unshuffle();

        assertEquals(2, queue.size());
        assertFalse(queue.getStreams().contains(pending));
        assertEquals("C", queue.getItem(1).getUrl());
    }

    @Test
    public void shuffledQueueRemovesPendingTailFromBackupToo() {
        final SinglePlayQueue queue = new SinglePlayQueue(
                List.of(info("A"), info("B"), info("D")), 0);
        final PlayQueueItem selected = queue.getItem();
        queue.shuffle();
        final PlayQueueItem pendingTail = queue.getItem(queue.size() - 1);
        pendingTail.setAutoQueued(true);

        queue.append(List.of(item("C")));
        queue.unshuffle();

        assertEquals(3, queue.size());
        assertSame(selected, queue.getItem());
        assertFalse(queue.getStreams().contains(pendingTail));
        assertTrue(queue.getStreams().stream().anyMatch(entry -> "C".equals(entry.getUrl())));
    }

    @Test
    public void automaticAppendKeepsExistingRecommendation() {
        final SinglePlayQueue queue = queue(0, "A", "B");
        queue.getItem(1).setAutoQueued(true);
        final PlayQueueItem next = item("C");
        next.setAutoQueued(true);

        queue.append(List.of(next));

        assertEquals(3, queue.size());
        assertTrue(queue.getItem(1).isAutoQueued());
        assertSame(next, queue.getItem(2));
    }

    @Test
    public void manualEnqueueNextKeepsSelectedRecommendation() {
        final SinglePlayQueue queue = queue(1, "A", "B");
        final PlayQueueItem selected = queue.getItem();
        selected.setAutoQueued(true);

        queue.enqueueNext(item("C"), false);

        assertSame(selected, queue.getItem());
        assertEquals("C", queue.getItem(queue.getIndex() + 1).getUrl());
    }

    private static SinglePlayQueue queue(final int index, final String first,
                                         final String second) {
        return new SinglePlayQueue(List.of(info(first), info(second)), index);
    }

    private static PlayQueueItem item(final String url) {
        return new PlayQueueItem(info(url));
    }

    private static StreamInfoItem info(final String url) {
        return new StreamInfoItem(0, url, url, StreamType.VIDEO_STREAM);
    }
}
