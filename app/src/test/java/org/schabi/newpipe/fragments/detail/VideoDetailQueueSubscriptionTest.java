package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.util.Log;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueEvent;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.processors.PublishProcessor;

/** Queue replacement must release subscriptions owned by the previous detail entry. */
public class VideoDetailQueueSubscriptionTest {
    @Test
    public void replacingQueueAndRefreshingSameQueueNeverRetainsOldSubscription()
            throws Exception {
        final VideoDetailFragment fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        final Field binding = VideoDetailFragment.class.getDeclaredField("binding");
        binding.setAccessible(true);
        binding.set(fragment, mock(FragmentVideoDetailBinding.class));

        final PublishProcessor<PlayQueueEvent> firstEvents = PublishProcessor.create();
        final PublishProcessor<PlayQueueEvent> secondEvents = PublishProcessor.create();
        final AtomicInteger firstSubscriptions = new AtomicInteger();
        final AtomicInteger firstCancellations = new AtomicInteger();
        final AtomicInteger secondSubscriptions = new AtomicInteger();
        final AtomicInteger secondCancellations = new AtomicInteger();
        final PlayQueue first = mock(PlayQueue.class);
        final PlayQueue second = mock(PlayQueue.class);
        when(first.getBroadcastReceiver()).thenReturn(firstEvents
                .doOnSubscribe(subscription -> firstSubscriptions.incrementAndGet())
                .doOnCancel(firstCancellations::incrementAndGet));
        when(second.getBroadcastReceiver()).thenReturn(secondEvents
                .doOnSubscribe(subscription -> secondSubscriptions.incrementAndGet())
                .doOnCancel(secondCancellations::incrementAndGet));

        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            fragment.onQueueUpdate(first);
            assertTrue(firstEvents.hasSubscribers());
            fragment.onQueueUpdate(second);
            assertFalse("previous queue must not retain a detail observer",
                    firstEvents.hasSubscribers());
            assertEquals(1, firstCancellations.get());
            assertTrue(secondEvents.hasSubscribers());
            fragment.onQueueUpdate(second);
            assertEquals(2, secondSubscriptions.get());
            assertEquals(1, secondCancellations.get());
        }
    }

    @Test
    public void playerDisconnectReleasesCurrentQueueObserver() throws Exception {
        final VideoDetailFragment fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        final Field binding = VideoDetailFragment.class.getDeclaredField("binding");
        binding.setAccessible(true);
        binding.set(fragment, mock(FragmentVideoDetailBinding.class));
        final PublishProcessor<PlayQueueEvent> events = PublishProcessor.create();
        final PlayQueue queue = mock(PlayQueue.class);
        when(queue.getBroadcastReceiver()).thenReturn(events);
        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            fragment.onQueueUpdate(queue);
            assertTrue(events.hasSubscribers());
            binding.set(fragment, null);
            final Field handler = VideoDetailFragment.class.getDeclaredField("sleepTimerHandler");
            handler.setAccessible(true);
            handler.set(fragment, mock(Handler.class));
            fragment.onPlayerDisconnected();
            assertFalse(events.hasSubscribers());
        }
    }

    @Test
    public void selectingDifferentQueueReleasesOldObserverBeforePlaybackCallback()
            throws Exception {
        final VideoDetailFragment fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        final Field binding = VideoDetailFragment.class.getDeclaredField("binding");
        binding.setAccessible(true);
        binding.set(fragment, mock(FragmentVideoDetailBinding.class));
        final PublishProcessor<PlayQueueEvent> events = PublishProcessor.create();
        final PlayQueue first = mock(PlayQueue.class);
        final PlayQueue second = mock(PlayQueue.class);
        when(first.getBroadcastReceiver()).thenReturn(events);
        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            fragment.onQueueUpdate(first);
            assertTrue(events.hasSubscribers());
            fragment.setInitialData(1, "https://example.test/b", "B", second);
            assertFalse("old observer survives while new item is still loading",
                    events.hasSubscribers());
        }
    }
}
