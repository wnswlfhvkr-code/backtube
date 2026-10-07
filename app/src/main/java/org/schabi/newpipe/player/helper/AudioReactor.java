package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;

public class AudioReactor implements AudioManager.OnAudioFocusChangeListener, AnalyticsListener {

    private static final String TAG = "AudioFocusReactor";

    private static final float DUCK_AUDIO_TO = .2f;

    private static final int FOCUS_GAIN_TYPE = AudioManagerCompat.AUDIOFOCUS_GAIN;
    private static final int STREAM_TYPE = AudioManager.STREAM_MUSIC;

    private final ExoPlayer player;
    private final Context context;
    private final AudioManager audioManager;

    private final AudioFocusRequestCompat request;
    private float focusGain = 1.0f;
    private float playbackGain = 1.0f;
    private boolean resumeOnFocusGain;

    public AudioReactor(@NonNull final Context context,
                        @NonNull final ExoPlayer player) {
        this.player = player;
        this.context = context;
        this.audioManager = ContextCompat.getSystemService(context, AudioManager.class);
        player.addAnalyticsListener(this);

        request = new AudioFocusRequestCompat.Builder(FOCUS_GAIN_TYPE)
                //.setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this)
                .build();
    }

    public void dispose() {
        abandonAudioFocus();
        player.removeAnalyticsListener(this);
        notifyAudioSessionUpdate(false, player.getAudioSessionId());
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Audio Manager
    //////////////////////////////////////////////////////////////////////////*/

    public void requestAudioFocus() {
        if (AudioManagerCompat.requestAudioFocus(audioManager, request)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // A synchronous grant need not dispatch AUDIOFOCUS_GAIN. This is also the
            // notification/media-button resume path after an interruption.
            resumeOnFocusGain = false;
            restoreFocusGain();
        }
    }

    public void abandonAudioFocus() {
        // Explicit pause (including the sleep timer) must win over a late focus callback.
        resumeOnFocusGain = false;
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, request);
    }

    public int getVolume() {
        return audioManager.getStreamVolume(STREAM_TYPE);
    }

    public void setVolume(final int volume) {
        audioManager.setStreamVolume(STREAM_TYPE, volume, 0);
    }

    public int getMaxVolume() {
        return AudioManagerCompat.getStreamMaxVolume(audioManager, STREAM_TYPE);
    }

    public void setPlaybackGain(final float multiplier) {
        playbackGain = Math.max(0.0f, Math.min(1.0f, multiplier));
        applyVolume();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // AudioFocus
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onAudioFocusChange(final int focusChange) {
        Log.d(TAG, "onAudioFocusChange() called with: focusChange = [" + focusChange + "]");
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                onAudioFocusGain();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                onAudioFocusLossCanDuck();
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                onAudioFocusLoss();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Preserve the interrupted intent across repeated transient-loss callbacks.
                resumeOnFocusGain |= player.getPlayWhenReady();
                onAudioFocusLoss();
                break;
        }
    }

    private void onAudioFocusGain() {
        Log.d(TAG, "onAudioFocusGain() called");
        restoreFocusGain();

        final boolean shouldResume = resumeOnFocusGain;
        resumeOnFocusGain = false;
        if (shouldResume && PlayerHelper.isResumeAfterAudioFocusGain(context)) {
            player.play();
        }
    }

    private void onAudioFocusLoss() {
        Log.d(TAG, "onAudioFocusLoss() called");
        player.pause();
    }

    private void onAudioFocusLossCanDuck() {
        Log.d(TAG, "onAudioFocusLossCanDuck() called");
        focusGain = DUCK_AUDIO_TO;
        applyVolume();
    }

    private void restoreFocusGain() {
        // Audio recovery must not depend on UI animation frames in a background service.
        // Only remove our ducking; playbackGain still owns mute and sleep-timer fading.
        focusGain = 1.0f;
        applyVolume();
    }

    private void applyVolume() {
        player.setVolume(focusGain * playbackGain);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Audio Processing
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onAudioSessionIdChanged(@NonNull final EventTime eventTime,
                                        final int audioSessionId) {
        notifyAudioSessionUpdate(true, audioSessionId);
    }
    private void notifyAudioSessionUpdate(final boolean active, final int audioSessionId) {
        final Intent intent = new Intent(active
                ? AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                : AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
        context.sendBroadcast(intent);
    }
}
