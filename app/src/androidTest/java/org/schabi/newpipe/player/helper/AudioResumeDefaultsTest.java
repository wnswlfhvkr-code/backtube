package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;

import java.util.concurrent.FutureTask;

/** Real preference inflation and persistence; only the resume key is changed and restored. */
@RunWith(AndroidJUnit4.class)
public class AudioResumeDefaultsTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final SharedPreferences preferences =
            PreferenceManager.getDefaultSharedPreferences(context);
    private final String key = context.getString(R.string.resume_on_audio_focus_gain_key);
    private Boolean previous;

    @Before
    public void savePreference() {
        previous = preferences.contains(key) ? preferences.getBoolean(key, false) : null;
    }

    @After
    public void restorePreference() {
        final SharedPreferences.Editor editor = preferences.edit();
        if (previous == null) {
            editor.remove(key);
        } else {
            editor.putBoolean(key, previous);
        }
        editor.commit();
    }

    @Test
    public void absentSettingEnablesResumeBeforeDefaultsAreInitialized() {
        assertTrue(preferences.edit().remove(key).commit());
        assertFalse(preferences.contains(key));
        assertTrue(PlayerHelper.isResumeAfterAudioFocusGain(context));
    }

    @Test
    public void initializingFreshSettingEnablesResumeFromPreferenceResource() throws Exception {
        assertTrue(preferences.edit().remove(key).commit());
        initializeDefaults();
        assertTrue(preferences.contains(key));
        assertTrue(preferences.getBoolean(key, false));
        assertTrue(PlayerHelper.isResumeAfterAudioFocusGain(context));
    }

    @Test
    public void reinitializingDefaultsPreservesSavedOptOut() throws Exception {
        assertTrue(preferences.edit().putBoolean(key, false).commit());
        initializeDefaults();
        assertFalse(preferences.getBoolean(key, true));
        assertFalse(PlayerHelper.isResumeAfterAudioFocusGain(context));
    }

    private void initializeDefaults() throws Exception {
        final FutureTask<Void> task = new FutureTask<>(() -> {
            PreferenceManager.setDefaultValues(context, R.xml.video_audio_settings, true);
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        task.get();
    }
}
