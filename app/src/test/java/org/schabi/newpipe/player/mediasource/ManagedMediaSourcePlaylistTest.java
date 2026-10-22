package org.schabi.newpipe.player.mediasource;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.Test;

public class ManagedMediaSourcePlaylistTest {
    @Test
    public void removingFromEmptyPlaylistIsIgnored() {
        final ManagedMediaSourcePlaylist playlist = new ManagedMediaSourcePlaylist();
        playlist.remove(0);
        assertEquals(0, playlist.size());
    }

    @Test
    public void removingAtSizeIsIgnoredAndValidItemCanStillBeRemoved() {
        final ManagedMediaSourcePlaylist playlist = new ManagedMediaSourcePlaylist();
        playlist.append(mock(ManagedMediaSource.class));
        playlist.remove(-1);
        playlist.remove(playlist.size());
        assertEquals(1, playlist.size());
        playlist.remove(0);
        assertEquals(0, playlist.size());
    }
}
