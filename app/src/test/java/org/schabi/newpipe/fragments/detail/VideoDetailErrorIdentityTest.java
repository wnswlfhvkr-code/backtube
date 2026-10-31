package org.schabi.newpipe.fragments.detail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.fragments.BaseStateFragment;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.ui.MainPlayerUi;
import org.schabi.newpipe.player.ui.PlayerUiList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** A failed detail request must not keep another item's video behind its error panel. */
public class VideoDetailErrorIdentityTest {
    @Test
    public void failedSelectionHidesOnlyPreviousVideoView() throws Exception {
        final MainPlayerUi videoUi = mock(MainPlayerUi.class);
        final VideoDetailFragment fragment = fragmentShowing("https://example.test/a",
                "https://example.test/b", videoUi);

        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            fragment.handleError();
        }

        verify(videoUi).removeViewFromParent();
    }

    @Test
    public void failedRefreshOfPlayingItemKeepsItsVideoView() throws Exception {
        final MainPlayerUi videoUi = mock(MainPlayerUi.class);
        final VideoDetailFragment fragment = fragmentShowing("https://example.test/a",
                "https://example.test/a", videoUi);

        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            fragment.handleError();
        }

        verifyNoInteractions(videoUi);
    }

    @Test
    public void returningToPlayingItemAfterErrorSchedulesVideoReattachment() throws Exception {
        final MainPlayerUi videoUi = mock(MainPlayerUi.class);
        final VideoDetailFragment fragment = fragmentShowing("https://example.test/b",
                "https://example.test/b", videoUi, false);
        final Method showContent = VideoDetailFragment.class.getDeclaredMethod("showContent");
        showContent.setAccessible(true);

        try (MockedStatic<Looper> loopers = mockStatic(Looper.class);
             MockedConstruction<Handler> handlers = mockConstruction(Handler.class)) {
            loopers.when(Looper::getMainLooper).thenReturn(mock(Looper.class));
            showContent.invoke(fragment);
            org.junit.Assert.assertEquals(1, handlers.constructed().size());
            verify(handlers.constructed().get(0)).post(any(Runnable.class));
        }
    }

    private static VideoDetailFragment fragmentShowing(final String detailUrl,
                                                       final String playingUrl,
                                                       final MainPlayerUi videoUi)
            throws Exception {
        return fragmentShowing(detailUrl, playingUrl, videoUi, true);
    }

    private static VideoDetailFragment fragmentShowing(final String detailUrl,
                                                       final String playingUrl,
                                                       final MainPlayerUi videoUi,
                                                       final boolean attached)
            throws Exception {
        final VideoDetailFragment fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        set(BaseStateFragment.class, fragment, "isLoading", new AtomicBoolean());
        final FragmentVideoDetailBinding binding = mock(FragmentVideoDetailBinding.class);
        final FrameLayout placeholder = mock(FrameLayout.class);
        final PlayerBinding playerBinding = mock(PlayerBinding.class);
        final RelativeLayout playerRoot = mock(RelativeLayout.class);
        if (attached) {
            when(playerRoot.getParent()).thenReturn(placeholder);
        }
        when(playerBinding.getRoot()).thenReturn(playerRoot);
        when(videoUi.getBinding()).thenReturn(playerBinding);
        set(FragmentVideoDetailBinding.class, binding, "playerPlaceholder", placeholder);
        set(FragmentVideoDetailBinding.class, binding, "detailContentRootHiding",
                mock(LinearLayout.class));
        set(FragmentVideoDetailBinding.class, binding, "viewPager", mock(ViewPager.class));
        set(FragmentVideoDetailBinding.class, binding, "tabLayout", mock(TabLayout.class));
        set(VideoDetailFragment.class, fragment, "binding", binding);
        set(VideoDetailFragment.class, fragment, "serviceId", 0);
        set(VideoDetailFragment.class, fragment, "url", detailUrl);

        final Player player = mock(Player.class);
        final PlayQueue queue = mock(PlayQueue.class);
        final PlayQueueItem item = mock(PlayQueueItem.class);
        when(item.getServiceId()).thenReturn(0);
        when(item.getUrl()).thenReturn(playingUrl);
        when(queue.getItem()).thenReturn(item);
        when(player.getPlayQueue()).thenReturn(queue);
        when(player.videoPlayerSelected()).thenReturn(true);
        when(player.UIs()).thenReturn(new PlayerUiList(videoUi));
        set(VideoDetailFragment.class, fragment, "player", player);
        return fragment;
    }

    private static void set(final Class<?> owner, final Object object, final String name,
                            final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}

