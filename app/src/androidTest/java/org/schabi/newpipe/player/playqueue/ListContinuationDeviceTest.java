package org.schabi.newpipe.player.playqueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.fragments.list.BaseListFragment;
import org.schabi.newpipe.fragments.list.channel.ChannelTabFragment;
import org.schabi.newpipe.fragments.list.playlist.PlaylistFragment;
import org.schabi.newpipe.info_list.InfoListAdapter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** A queue built from loaded rows must continue after the currently loaded page. */
public class ListContinuationDeviceTest {
    @Test
    public void channelTabQueueUsesTheCurrentPageAfterRowsHaveLoaded() throws Exception {
        final ListLinkHandler tab = handler("videos", "/channel/videos");
        final ChannelTabInfo info = new ChannelTabInfo(1, tab);
        info.setNextPage(new Page("page-2"));
        final ChannelTabFragment fragment = ChannelTabFragment.getInstance(1, tab, "Channel");
        set(BaseListInfoFragment.class, fragment, "currentInfo", info);
        set(BaseListInfoFragment.class, fragment, "currentNextPage", new Page("page-3"));
        set(BaseListFragment.class, fragment, "infoListAdapter", adapter(item("a"), item("b")));

        final ChannelTabPlayQueue queue = (ChannelTabPlayQueue) fragment.getPlayQueue();
        assertEquals("page-3", queue.nextPage.getUrl());
        assertEquals(2, queue.getStreams().size());
        assertFalse(queue.isComplete());

        set(BaseListInfoFragment.class, fragment, "currentNextPage", null);
        final ChannelTabPlayQueue lastPage = (ChannelTabPlayQueue) fragment.getPlayQueue();
        assertEquals(null, lastPage.nextPage);
        assertTrue(lastPage.isComplete());
    }

    @Test
    public void playlistQueueKeepsSelectedIndexAndLatestPage() throws Exception {
        final ListLinkHandler link = handler("playlist", "/playlist");
        final PlaylistInfo info = playlistInfo(link);
        info.setNextPage(new Page("page-2"));
        final PlaylistFragment fragment = new PlaylistFragment();
        set(BaseListInfoFragment.class, fragment, "currentInfo", info);
        set(BaseListInfoFragment.class, fragment, "currentNextPage", new Page("page-3"));
        set(BaseListFragment.class, fragment, "infoListAdapter",
                adapter(item("a"), item("b"), item("c")));

        final PlaylistPlayQueue queue = (PlaylistPlayQueue) playlistQueue(fragment, 2);
        assertEquals("page-3", queue.nextPage.getUrl());
        assertEquals(2, queue.getIndex());
        assertEquals(3, queue.getStreams().size());

        set(BaseListInfoFragment.class, fragment, "currentNextPage", null);
        final PlaylistPlayQueue lastPage = (PlaylistPlayQueue) playlistQueue(fragment, 2);
        assertEquals(null, lastPage.nextPage);
        assertTrue(lastPage.isComplete());
        assertEquals(2, lastPage.getIndex());
    }

    private static PlayQueue playlistQueue(final PlaylistFragment fragment, final int index)
            throws Exception {
        final Method method = PlaylistFragment.class.getDeclaredMethod("getPlayQueue", int.class);
        method.setAccessible(true);
        return (PlayQueue) method.invoke(fragment, index);
    }

    private static PlaylistInfo playlistInfo(final ListLinkHandler link) throws Exception {
        final Constructor<PlaylistInfo> constructor = PlaylistInfo.class.getDeclaredConstructor(
                int.class, ListLinkHandler.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(1, link, "Playlist");
    }

    private static ListLinkHandler handler(final String id, final String path) {
        final String url = "https://example.invalid" + path;
        return new ListLinkHandler(id, url, url, List.of(id), "");
    }

    private static InfoListAdapter adapter(final StreamInfoItem... items) {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final InfoListAdapter adapter = new InfoListAdapter(context);
        adapter.addInfoItemList(List.of(items));
        return adapter;
    }

    private static StreamInfoItem item(final String id) {
        return new StreamInfoItem(1, "/watch/" + id, id, StreamType.VIDEO_STREAM);
    }

    private static void set(final Class<?> owner, final Object target, final String name,
                            final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
