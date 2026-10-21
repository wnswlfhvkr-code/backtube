package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.error.ErrorPanelHelper;
import org.schabi.newpipe.fragments.BaseStateFragment;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.OfflinePlayQueue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Exercises the real restored-fragment worker while UI and extraction are boundaries. */
public class OfflineDetailRestorationTest {
    @Test
    public void restoredVideoQueueLoadsLocalMetadataInsteadOfRedirectingToAudio() throws Exception {
        final VideoDetailFragment fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        final AppCompatActivity activity = mock(AppCompatActivity.class);
        final String url = "backtube-offline:restored-video";
        final OfflinePlayQueue queue = new OfflinePlayQueue(List.of(new StreamInfoItem(0,
                url, "Saved video", StreamType.VIDEO_STREAM)), 0);
        set(BaseFragment.class, fragment, "activity", activity);
        set(VideoDetailFragment.class, fragment, "url", url);
        set(VideoDetailFragment.class, fragment, "playQueue", queue);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        try (MockedStatic<PreferenceManager> preferences = mockStatic(PreferenceManager.class);
                MockedStatic<ExtractorHelper> extraction = mockStatic(ExtractorHelper.class);
                MockedStatic<NavigationHelper> navigation = mockStatic(NavigationHelper.class)) {
            preferences.when(() -> PreferenceManager.getDefaultSharedPreferences(activity))
                    .thenReturn(mock(SharedPreferences.class));
            extraction.when(() -> ExtractorHelper.getStreamInfo(0, url, false))
                    .thenReturn(Single.never());
            final Method worker = VideoDetailFragment.class
                    .getDeclaredMethod("runWorker", boolean.class, boolean.class);
            worker.setAccessible(true);
            worker.invoke(fragment, false, false);
            extraction.verify(() -> ExtractorHelper.getStreamInfo(0, url, false));
            navigation.verifyNoInteractions();
        } finally {
            RxAndroidPlugins.reset();
        }
    }

    @Test
    public void restoredInternalDetailRedirectsOnceWithoutStartingOnlineWorker() throws Exception {
        final VideoDetailFragment fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        final AppCompatActivity activity = mock(AppCompatActivity.class);
        final AtomicBoolean loading = new AtomicBoolean(true);
        final String url = "backtube-offline:restored-fixture";
        set(BaseFragment.class, fragment, "activity", activity);
        set(BaseStateFragment.class, fragment, "isLoading", loading);
        set(BaseStateFragment.class, fragment, "errorPanelHelper", mock(ErrorPanelHelper.class));
        set(VideoDetailFragment.class, fragment, "url", url);
        set(VideoDetailFragment.class, fragment, "title", "Saved title");
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        try (MockedStatic<PreferenceManager> preferences = mockStatic(PreferenceManager.class);
                MockedStatic<ExtractorHelper> extraction = mockStatic(ExtractorHelper.class);
                MockedStatic<NavigationHelper> navigation = mockStatic(NavigationHelper.class)) {
            preferences.when(() -> PreferenceManager.getDefaultSharedPreferences(activity))
                    .thenReturn(mock(SharedPreferences.class));
            extraction.when(() -> ExtractorHelper.getStreamInfo(
                            anyInt(), anyString(), anyBoolean()))
                    .thenReturn(Single.never());
            final Method worker = VideoDetailFragment.class
                    .getDeclaredMethod("runWorker", boolean.class, boolean.class);
            worker.setAccessible(true);
            worker.invoke(fragment, false, false);
            worker.invoke(fragment, false, false);
            extraction.verifyNoInteractions();
            navigation.verify(() -> NavigationHelper.openVideoDetail(activity,
                    0, url, "Saved title", null, false, false), times(1));
            assertFalse(loading.get());
        } finally {
            RxAndroidPlugins.reset();
        }
    }

    private static void set(final Class<?> owner, final Object target, final String name,
                            final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
