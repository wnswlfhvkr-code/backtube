package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;

/** Exercises the real settings reader with the Android preference boundary replaced. */
public class AudioResumePreferenceTest {
    @Test
    public void missingPreferenceEnablesAutomaticResume() {
        assertTrue(readResumeSetting(null));
    }

    @Test
    public void explicitOptOutRemainsDisabled() {
        assertFalse(readResumeSetting(false));
    }

    private boolean readResumeSetting(final Boolean stored) {
        final Context context = mock(Context.class);
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final String key = "resume_on_audio_focus_gain";
        when(context.getString(R.string.resume_on_audio_focus_gain_key)).thenReturn(key);
        when(preferences.getBoolean(eq(key), anyBoolean())).thenAnswer(call ->
                stored == null ? call.<Boolean>getArgument(1) : stored);
        try (MockedStatic<PreferenceManager> manager = mockStatic(PreferenceManager.class)) {
            manager.when(() -> PreferenceManager.getDefaultSharedPreferences(context))
                    .thenReturn(preferences);
            return PlayerHelper.isResumeAfterAudioFocusGain(context);
        }
    }
}
