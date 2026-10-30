package org.schabi.newpipe.player.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.android.exoplayer2.PlaybackParameters;

import org.junit.Test;

public class TemporaryPlaybackSpeedControllerTest {
    private final FakePlayback playback = new FakePlayback();
    private boolean indicatorVisible;
    private final TemporaryPlaybackSpeedController controller =
            new TemporaryPlaybackSpeedController(playback, visible -> indicatorVisible = visible);

    @Test
    public void restoresOriginalSpeedAndPitchOnRelease() {
        playback.parameters = new PlaybackParameters(1.25f, 1.1f);
        assertTrue(controller.begin());
        assertEquals(2.0f, playback.parameters.speed, 0.001f);
        assertEquals(1.1f, playback.parameters.pitch, 0.001f);
        assertTrue(indicatorVisible);

        controller.end();
        assertEquals(1.25f, playback.parameters.speed, 0.001f);
        assertEquals(1.1f, playback.parameters.pitch, 0.001f);
        assertFalse(indicatorVisible);
    }

    @Test
    public void cancelRestoresEvenWhenOriginalSpeedIsTwo() {
        playback.parameters = new PlaybackParameters(2.0f, 1.0f);
        assertTrue(controller.begin());
        controller.end();
        assertEquals(2.0f, playback.parameters.speed, 0.001f);
        assertFalse(indicatorVisible);
    }

    @Test
    public void doesNotStartWhileNotPlayingVideo() {
        playback.playable = false;
        assertFalse(controller.begin());
        assertFalse(indicatorVisible);
        assertEquals(0, playback.setCount);
    }

    @Test
    public void doesNotRestoreOldSpeedToReplacementEngine() {
        playback.parameters = new PlaybackParameters(1.5f);
        assertTrue(controller.begin());
        playback.engine = new Object();
        playback.parameters = new PlaybackParameters(1.0f);
        controller.end();
        assertEquals(1.0f, playback.parameters.speed, 0.001f);
        assertFalse(indicatorVisible);
    }

    @Test
    public void repeatedBeginKeepsFirstOriginalSpeed() {
        playback.parameters = new PlaybackParameters(1.5f);
        assertTrue(controller.begin());
        assertFalse(controller.begin());
        controller.end();
        assertEquals(1.5f, playback.parameters.speed, 0.001f);
    }

    private static final class FakePlayback implements TemporaryPlaybackSpeedController.Playback {
        private Object engine = new Object();
        private boolean playable = true;
        private PlaybackParameters parameters = PlaybackParameters.DEFAULT;
        private int setCount;

        @Override
        public Object engine() {
            return engine;
        }

        @Override
        public boolean isPlayable() {
            return playable;
        }

        @Override
        public PlaybackParameters parameters() {
            return parameters;
        }

        @Override
        public void setParameters(final PlaybackParameters value) {
            parameters = value;
            setCount++;
        }
    }
}
