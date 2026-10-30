package org.schabi.newpipe.player.gesture;

import com.google.android.exoplayer2.PlaybackParameters;

/** Keeps a touch-only speed change separate from the saved playback preference. */
public final class TemporaryPlaybackSpeedController {
    public interface Playback {
        Object engine();
        boolean isPlayable();
        PlaybackParameters parameters();
        void setParameters(PlaybackParameters value);
    }

    public interface Indicator {
        void setVisible(boolean visible);
    }

    private final Playback playback;
    private final Indicator indicator;
    private Object activeEngine;
    private PlaybackParameters originalParameters;

    public TemporaryPlaybackSpeedController(final Playback playback, final Indicator indicator) {
        this.playback = playback;
        this.indicator = indicator;
    }

    public boolean begin() {
        if (activeEngine != null || !playback.isPlayable() || playback.engine() == null) {
            return false;
        }
        activeEngine = playback.engine();
        originalParameters = playback.parameters();
        playback.setParameters(new PlaybackParameters(2.0f, originalParameters.pitch));
        indicator.setVisible(true);
        return true;
    }

    public void end() {
        if (activeEngine == null) {
            return;
        }
        if (activeEngine == playback.engine()) {
            playback.setParameters(originalParameters);
        }
        activeEngine = null;
        originalParameters = null;
        indicator.setVisible(false);
    }

    public boolean isActive() {
        return activeEngine != null;
    }
}
