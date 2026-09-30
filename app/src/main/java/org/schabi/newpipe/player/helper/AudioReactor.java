package org.schabi.newpipe.player.helper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;

public class AudioReactor implements AudioManager.OnAudioFocusChangeListener, AnalyticsListener {

    private static final String TAG = "AudioFocusReactor";

    private static final int DUCK_DURATION = 1500;
    private static final float DUCK_AUDIO_TO = .2f;

    private static final int FOCUS_GAIN_TYPE = AudioManagerCompat.AUDIOFOCUS_GAIN;
    private static final int STREAM_TYPE = AudioManager.STREAM_MUSIC;

    private final ExoPlayer player;
    private final Context context;
    private final AudioManager audioManager;

    private final AudioFocusRequestCompat request;
    private float focusGain = 1.0f;
    private float playbackGain = 1.0f;
    @Nullable
    private ValueAnimator focusAnimator;

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
        cancelFocusAnimation();
        abandonAudioFocus();
        player.removeAnalyticsListener(this);
        notifyAudioSessionUpdate(false, player.getAudioSessionId());
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Audio Manager
    //////////////////////////////////////////////////////////////////////////*/

    public void requestAudioFocus() {
        AudioManagerCompat.requestAudioFocus(audioManager, request);
    }

    public void abandonAudioFocus() {
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
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                onAudioFocusLoss();
                break;
        }
    }

    private void onAudioFocusGain() {
        Log.d(TAG, "onAudioFocusGain() called");
        animateFocusGain(DUCK_AUDIO_TO, 1.0f);

        if (PlayerHelper.isResumeAfterAudioFocusGain(context)) {
            player.play();
        }
    }

    private void onAudioFocusLoss() {
        Log.d(TAG, "onAudioFocusLoss() called");
        cancelFocusAnimation();
        player.pause();
    }

    private void onAudioFocusLossCanDuck() {
        Log.d(TAG, "onAudioFocusLossCanDuck() called");
        // Set the volume to 1/10 on ducking
        cancelFocusAnimation();
        focusGain = DUCK_AUDIO_TO;
        applyVolume();
    }

    private void animateFocusGain(final float from, final float to) {
        cancelFocusAnimation();
        focusGain = from;
        applyVolume();

        final ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        focusAnimator = animator;
        animator.setDuration(AudioReactor.DUCK_DURATION);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(final Animator animation) {
                if (focusAnimator == animator) {
                    focusGain = to;
                    focusAnimator = null;
                    applyVolume();
                }
            }
        });
        animator.addUpdateListener(animation -> {
            if (focusAnimator == animator) {
                focusGain = (float) animation.getAnimatedValue();
                applyVolume();
            }
        });
        animator.start();
    }

    private void cancelFocusAnimation() {
        final ValueAnimator animator = focusAnimator;
        focusAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
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
