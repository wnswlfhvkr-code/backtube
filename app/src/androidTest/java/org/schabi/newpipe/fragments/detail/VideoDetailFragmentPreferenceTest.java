package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;

import java.lang.reflect.Field;
import java.util.concurrent.FutureTask;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VideoDetailFragmentPreferenceTest {
    @Test
    public void queuedPreferenceChangeAfterDetachDoesNotRequireContext() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final FutureTask<Void> callback = new FutureTask<>(() -> {
            final VideoDetailFragment fragment = new VideoDetailFragment();
            assertNull(fragment.getContext());
            final Field field = VideoDetailFragment.class
                    .getDeclaredField("preferenceChangeListener");
            field.setAccessible(true);
            final SharedPreferences.OnSharedPreferenceChangeListener listener =
                    (SharedPreferences.OnSharedPreferenceChangeListener) field.get(fragment);
            // Android can already have queued a listener before it is unregistered.
            listener.onSharedPreferenceChanged(
                    PreferenceManager.getDefaultSharedPreferences(context),
                    context.getString(R.string.show_comments_key));
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(callback);
        callback.get();
    }
}
