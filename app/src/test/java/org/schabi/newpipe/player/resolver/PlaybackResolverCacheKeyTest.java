package org.schabi.newpipe.player.resolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

/** Cache identity separates providers while retaining stable keys for refreshed media URLs. */
public class PlaybackResolverCacheKeyTest {
    @Test
    public void audioKeyIncludesServiceButIgnoresRefreshedSignedUrl() {
        final AudioStream original = audio("https://example.test/audio?sig=old");
        final AudioStream refreshed = audio("https://example.test/audio?sig=new");
        final String first = PlaybackResolver.cacheKeyOf(info(1), original);
        assertNotEquals(first, PlaybackResolver.cacheKeyOf(info(2), original));
        assertEquals(first, PlaybackResolver.cacheKeyOf(info(1), refreshed));
        assertTrue(first.startsWith("1 "));
    }

    @Test
    public void videoKeyIncludesServiceAndStillSeparatesResolution() {
        final String first = PlaybackResolver.cacheKeyOf(info(1), video("720p"));
        assertNotEquals(first, PlaybackResolver.cacheKeyOf(info(2), video("720p")));
        assertNotEquals(first, PlaybackResolver.cacheKeyOf(info(1), video("1080p")));
        assertTrue(first.startsWith("1 "));
    }

    @Test
    public void unknownFormatAndBitrateStillUseContentAsFallback() {
        final AudioStream first = new AudioStream.Builder().setId("unknown")
                .setContent("file:///generated/offline-a", true)
                .setAverageBitrate(AudioStream.UNKNOWN_BITRATE).build();
        final AudioStream second = new AudioStream.Builder().setId("unknown")
                .setContent("file:///generated/offline-b", true)
                .setAverageBitrate(AudioStream.UNKNOWN_BITRATE).build();
        assertNotEquals(PlaybackResolver.cacheKeyOf(info(1), first),
                PlaybackResolver.cacheKeyOf(info(1), second));
    }

    @Test
    public void audioTrackIdentityRemainsPartOfTheKey() {
        final AudioStream left = new AudioStream.Builder().setId("same-stream-id")
                .setContent("https://example.test/audio", true)
                .setMediaFormat(MediaFormat.M4A).setAverageBitrate(128)
                .setAudioTrackId("left").build();
        final AudioStream right = new AudioStream.Builder().setId("same-stream-id")
                .setContent("https://example.test/audio", true)
                .setMediaFormat(MediaFormat.M4A).setAverageBitrate(128)
                .setAudioTrackId("right").build();
        assertNotEquals(PlaybackResolver.cacheKeyOf(info(1), left),
                PlaybackResolver.cacheKeyOf(info(1), right));
    }

    private static StreamInfo info(final int serviceId) {
        return new StreamInfo(serviceId, "same-info-id", "https://example.test/watch",
                StreamType.VIDEO_STREAM, "Same item", "", 0);
    }

    private static AudioStream audio(final String url) {
        return new AudioStream.Builder().setId("same-stream-id").setContent(url, true)
                .setMediaFormat(MediaFormat.M4A).setAverageBitrate(128).build();
    }

    private static VideoStream video(final String resolution) {
        return new VideoStream.Builder().setId("same-stream-id")
                .setContent("https://example.test/video", true)
                .setMediaFormat(MediaFormat.MPEG_4).setResolution(resolution)
                .setIsVideoOnly(false).build();
    }
}
