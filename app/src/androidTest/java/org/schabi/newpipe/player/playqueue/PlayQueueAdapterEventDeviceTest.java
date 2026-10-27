package org.schabi.newpipe.player.playqueue;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Checks real Android RecyclerView notifications against queue size changes. */
@RunWith(AndroidJUnit4.class)
public class PlayQueueAdapterEventDeviceTest {
    @Test
    public void appendReportsValidPositionOrRefreshesSnapshot() {
        try (Fixture fixture = new Fixture()) {
            fixture.onMain(() -> fixture.queue.append(List.of(item("B"))));
            fixture.idle();

            assertEquals(2, fixture.adapter.getItemCount());
            assertTrue(fixture.observer.changes.get() > 0
                    || fixture.observer.insertions.equals(List.of("1:1")));
        }
    }

    @Test
    public void rapidAppendsDoNotReportPositionsBeyondTheirSnapshot() {
        try (Fixture fixture = new Fixture()) {
            fixture.onMain(() -> {
                fixture.queue.append(List.of(item("B")));
                fixture.queue.append(List.of(item("C")));
            });
            fixture.idle();

            assertEquals(3, fixture.adapter.getItemCount());
            assertTrue(fixture.observer.changes.get() > 0
                    || fixture.observer.insertions.equals(List.of("1:1", "2:1")));
        }
    }

    @Test
    public void replacingPendingRecommendationKeepsAdapterCardinality() {
        try (Fixture fixture = new Fixture()) {
            fixture.onMain(() -> {
                final PlayQueueItem recommendation = item("B");
                recommendation.setAutoQueued(true);
                fixture.queue.append(List.of(recommendation));
            });
            fixture.idle();
            fixture.observer.reset();

            fixture.onMain(() -> fixture.queue.append(List.of(item("C"))));
            fixture.idle();

            assertEquals(2, fixture.adapter.getItemCount());
            assertEquals("C", fixture.queue.getItem(1).getUrl());
            assertTrue(fixture.observer.changes.get() > 0
                    || fixture.observer.insertions.equals(List.of("1:1")));
        }
    }

    private static PlayQueueItem item(final String url) {
        return new PlayQueueItem(new StreamInfoItem(0, url, url, StreamType.VIDEO_STREAM));
    }

    private static final class Notifications extends RecyclerView.AdapterDataObserver {
        final AtomicInteger changes = new AtomicInteger();
        final List<String> insertions = new ArrayList<>();

        @Override
        public void onChanged() {
            changes.incrementAndGet();
        }

        @Override
        public void onItemRangeInserted(final int positionStart, final int itemCount) {
            insertions.add(positionStart + ":" + itemCount);
        }

        void reset() {
            changes.set(0);
            insertions.clear();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final SinglePlayQueue queue = new SinglePlayQueue(item("A"));
        final Notifications observer = new Notifications();
        PlayQueueAdapter adapter;

        Fixture() {
            onMain(() -> {
                queue.init();
                adapter = new PlayQueueAdapter(InstrumentationRegistry.getInstrumentation()
                        .getTargetContext(), queue);
                adapter.registerAdapterDataObserver(observer);
            });
            idle();
            observer.reset();
        }

        void onMain(final Runnable action) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
        }

        void idle() {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }

        @Override
        public void close() {
            onMain(() -> {
                adapter.unregisterAdapterDataObserver(observer);
                adapter.dispose();
                queue.dispose();
            });
        }
    }
}
