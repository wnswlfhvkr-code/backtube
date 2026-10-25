package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.views.NewPipeTextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Deferred callbacks may only act on the current item and the view that scheduled them. */
public class VideoDetailDeferredCallbackTest {
    private static final class RenderReached extends AssertionError { }

    @Test
    public void sameTitleForCurrentItemStillRendersItsMetadata() throws Exception {
        final VideoDetailFragment fragment = fragmentWithActivity();
        final StreamInfo current = info(1, "https://example.test/b", "Shared title");
        set(VideoDetailFragment.class, fragment, "currentInfo", current);
        final FragmentVideoDetailBinding binding = mock(FragmentVideoDetailBinding.class);
        final NewPipeTextView title = mock(NewPipeTextView.class);
        set(FragmentVideoDetailBinding.class, binding, "detailVideoTitleView", title);
        when(title.getText()).thenReturn("Shared title");
        set(VideoDetailFragment.class, fragment, "binding", binding);
        doAnswer(invocation -> {
            throw new RenderReached();
        }).when(fragment).showLoading();

        final Runnable callback = delayedInfo(fragment, current);
        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            org.junit.Assert.assertThrows(RenderReached.class, callback::run);
        }
    }

    @Test
    public void oldItemCallbackDoesNotRenderAfterNewSelection() throws Exception {
        final VideoDetailFragment fragment = fragmentWithActivity();
        final StreamInfo old = info(1, "https://example.test/a", "A");
        final StreamInfo current = info(1, "https://example.test/b", "B");
        set(VideoDetailFragment.class, fragment, "currentInfo", current);
        final FragmentVideoDetailBinding binding = mock(FragmentVideoDetailBinding.class);
        final NewPipeTextView title = mock(NewPipeTextView.class);
        set(FragmentVideoDetailBinding.class, binding, "detailVideoTitleView", title);
        when(title.getText()).thenReturn("B");
        set(VideoDetailFragment.class, fragment, "binding", binding);
        doAnswer(invocation -> {
            throw new RenderReached();
        }).when(fragment).showLoading();

        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            delayedInfo(fragment, old).run();
        }
    }

    @Test
    public void sameUrlFromAnotherServiceIsNotTheCurrentItem() throws Exception {
        final VideoDetailFragment fragment = fragmentWithActivity();
        final StreamInfo old = info(1, "https://example.test/item", "Old");
        final StreamInfo current = info(2, "https://example.test/item", "New");
        set(VideoDetailFragment.class, fragment, "currentInfo", current);
        final FragmentVideoDetailBinding binding = mock(FragmentVideoDetailBinding.class);
        final NewPipeTextView title = mock(NewPipeTextView.class);
        set(FragmentVideoDetailBinding.class, binding, "detailVideoTitleView", title);
        when(title.getText()).thenReturn("New");
        set(VideoDetailFragment.class, fragment, "binding", binding);
        doAnswer(invocation -> {
            throw new RenderReached();
        }).when(fragment).showLoading();

        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            delayedInfo(fragment, old).run();
        }
    }

    @Test
    public void postedTabUpdateDoesNotTouchReplacementView() throws Exception {
        final VideoDetailFragment fragment = fragmentWithActivity();
        final TabAdapter adapter = mock(TabAdapter.class);
        when(adapter.getCount()).thenReturn(2);
        set(VideoDetailFragment.class, fragment, "pageAdapter", adapter);
        final FragmentVideoDetailBinding oldBinding = mock(FragmentVideoDetailBinding.class);
        final TabLayout oldTab = mock(TabLayout.class);
        final ViewPager oldPager = mock(ViewPager.class);
        set(FragmentVideoDetailBinding.class, oldBinding, "tabLayout", oldTab);
        set(FragmentVideoDetailBinding.class, oldBinding, "viewPager", oldPager);
        when(oldPager.getVisibility()).thenReturn(View.VISIBLE);
        final AtomicReference<Runnable> pending = new AtomicReference<>();
        when(oldTab.post(any(Runnable.class))).thenAnswer(invocation -> {
            pending.set(invocation.getArgument(0));
            return true;
        });
        set(VideoDetailFragment.class, fragment, "binding", oldBinding);
        fragment.updateTabLayoutVisibility();
        assertNotNull(pending.get());

        final FragmentVideoDetailBinding replacement = mock(FragmentVideoDetailBinding.class);
        set(VideoDetailFragment.class, fragment, "binding", replacement);
        pending.get().run();
    }

    private static VideoDetailFragment fragmentWithActivity() throws Exception {
        final VideoDetailFragment fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        final AppCompatActivity activity = mock(AppCompatActivity.class);
        set(BaseFragment.class, fragment, "activity", activity);
        doReturn(activity).when(fragment).getActivity();
        return fragment;
    }

    private static StreamInfo info(final int serviceId, final String url, final String title) {
        final StreamInfo info = mock(StreamInfo.class);
        when(info.getServiceId()).thenReturn(serviceId);
        when(info.getUrl()).thenReturn(url);
        when(info.getName()).thenReturn(title);
        return info;
    }

    private static Runnable delayedInfo(final VideoDetailFragment fragment,
                                        final StreamInfo info) throws Exception {
        final AtomicReference<Runnable> pending = new AtomicReference<>();
        try (MockedStatic<Looper> loopers = mockStatic(Looper.class);
             MockedConstruction<Handler> handlers = mockConstruction(Handler.class,
                     (handler, context) -> when(handler.postDelayed(any(Runnable.class),
                             anyLong())).thenAnswer(invocation -> {
                                 pending.set(invocation.getArgument(0));
                                 return true;
                             }))) {
            loopers.when(Looper::getMainLooper).thenReturn(mock(Looper.class));
            final Method method = VideoDetailFragment.class.getDeclaredMethod(
                    "prepareAndHandleInfoIfNeededAfterDelay", StreamInfo.class,
                    boolean.class, long.class);
            method.setAccessible(true);
            method.invoke(fragment, info, true, 200L);
        }
        assertNotNull(pending.get());
        return pending.get();
    }

    private static void set(final Class<?> owner, final Object object, final String name,
                            final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
