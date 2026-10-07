package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.when;

import android.animation.ValueAnimator;
import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;

import com.google.android.exoplayer2.ExoPlayer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/** Exercises the real reactor with platform boundaries replaced on the JVM. */
public class AudioReactorTest {
    private final Context context = mock(Context.class);
    private final ExoPlayer player = mock(ExoPlayer.class);
    private final AudioManager audioManager = mock(AudioManager.class);
    private MockedStatic<Log> log;
    private MockedStatic<ContextCompat> contextCompat;
    private MockedStatic<AudioManagerCompat> audioManagerCompat;
    private MockedStatic<PlayerHelper> helper;
    private MockedStatic<ValueAnimator> animations;
    private MockedConstruction<AudioFocusRequestCompat.Builder> builders;
    private AudioReactor reactor;
    private float volume = 1.0f;
    private boolean playing = true;
    private int focusResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

    @Before
    public void setUp() {
        log = mockStatic(Log.class);
        contextCompat = mockStatic(ContextCompat.class);
        audioManagerCompat = mockStatic(AudioManagerCompat.class);
        helper = mockStatic(PlayerHelper.class);
        // No frame callbacks: this also models a background process with no animation frames.
        animations = mockStatic(ValueAnimator.class);
        animations.when(() -> ValueAnimator.ofFloat(anyFloat(), anyFloat()))
                .thenReturn(mock(ValueAnimator.class));
        builders = mockConstruction(AudioFocusRequestCompat.Builder.class,
                withSettings().defaultAnswer(RETURNS_SELF));
        contextCompat.when(() -> ContextCompat.getSystemService(context, AudioManager.class))
                .thenReturn(audioManager);
        audioManagerCompat.when(() -> AudioManagerCompat.requestAudioFocus(audioManager, null))
                .thenAnswer(invocation -> focusResult);
        helper.when(() -> PlayerHelper.isResumeAfterAudioFocusGain(context)).thenReturn(true);
        when(player.getPlayWhenReady()).thenAnswer(invocation -> playing);
        doAnswer(invocation -> {
            volume = invocation.getArgument(0);
            return null;
        }).when(player).setVolume(anyFloat());
        doAnswer(invocation -> {
            playing = false;
            return null;
        }).when(player).pause();
        doAnswer(invocation -> {
            playing = true;
            return null;
        }).when(player).play();
        reactor = new AudioReactor(context, player);
    }

    @After
    public void tearDown() {
        if (builders != null) {
            builders.close();
        }
        if (animations != null) {
            animations.close();
        }
        if (helper != null) {
            helper.close();
        }
        if (audioManagerCompat != null) {
            audioManagerCompat.close();
        }
        if (contextCompat != null) {
            contextCompat.close();
        }
        if (log != null) {
            log.close();
        }
    }

    @Test
    public void grantedResumeBeforeGainRestoresDuckedVolume() {
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertEquals(.2f, volume, .001f);
        reactor.requestAudioFocus();
        player.play();
        assertEquals(1.0f, volume, .001f);
        assertTrue(playing);
    }

    @Test
    public void deniedRequestDoesNotClearDucking() {
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        focusResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        reactor.requestAudioFocus();
        assertEquals(.2f, volume, .001f);
    }

    @Test
    public void gainRestoresVolumeWithoutAnimationFrames() {
        reactor.setPlaybackGain(.6f);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals(.12f, volume, .001f);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(.6f, volume, .001f);
    }

    @Test
    public void repeatedInterruptionsRestoreGainAndResumeOnlyInterruptedPlayback() {
        for (int i = 0; i < 3; i++) {
            reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
            reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            assertFalse(playing);
            reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
            assertTrue(playing);
            assertEquals(1.0f, volume, .001f);
        }
    }

    @Test
    public void intentionalPauseCancelsResumeOnLateGain() {
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        reactor.abandonAudioFocus();
        player.pause();
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(playing);
    }

    @Test
    public void alreadyPausedPlaybackDoesNotResumeOnGain() {
        player.pause();
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(playing);
    }

    @Test
    public void permanentLossCancelsPendingResume() {
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(playing);
    }

    @Test
    public void gainWithAutomaticResumeDisabledStillRestoresVolume() {
        helper.when(() -> PlayerHelper.isResumeAfterAudioFocusGain(context)).thenReturn(false);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(playing);
        assertEquals(1.0f, volume, .001f);
        reactor.requestAudioFocus();
        player.play();
        assertTrue(playing);
        assertEquals(1.0f, volume, .001f);
    }

    @Test
    public void focusRecoveryPreservesMuteAndCurrentPlaybackGain() {
        reactor.setPlaybackGain(0.0f);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(0.0f, volume, .001f);
        reactor.setPlaybackGain(.4f);
        assertEquals(.4f, volume, .001f);
        reactor.requestAudioFocus();
        assertEquals(.4f, volume, .001f);
    }
}
