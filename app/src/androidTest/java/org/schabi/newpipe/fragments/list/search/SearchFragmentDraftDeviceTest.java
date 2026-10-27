package org.schabi.newpipe.fragments.list.search;

import static org.junit.Assert.assertEquals;

import android.os.Bundle;
import android.widget.EditText;

import androidx.test.platform.app.InstrumentationRegistry;

import com.livefront.bridge.Bridge;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.FutureTask;

/** Saving an unsubmitted editor draft must not change the active result query. */
public class SearchFragmentDraftDeviceTest {
    @Test
    public void savingDraftPreservesTheSubmittedQuery() throws Exception {
        onMain(() -> {
            final SearchFragment fragment = new SearchFragment();
            fragment.setUseDefaultStateSaving(false);
            fragment.searchString = "submitted A";
            final EditText editor = new EditText(InstrumentationRegistry.getInstrumentation()
                    .getTargetContext());
            editor.setText("draft B");
            try {
                final Field editorField = SearchFragment.class.getDeclaredField("searchEditText");
                editorField.setAccessible(true);
                editorField.set(fragment, editor);
                final Bundle saved = new Bundle();
                fragment.onSaveInstanceState(saved);
                final SearchFragment restored = new SearchFragment();
                Bridge.restoreInstanceState(restored, saved);
                assertEquals("submitted A", restored.searchString);
                assertEquals("draft B", restored.searchEditString);
            } catch (final ReflectiveOperationException e) {
                throw new AssertionError(e);
            }

            assertEquals("submitted A", fragment.searchString);
            assertEquals("draft B", fragment.searchEditString);
        });
    }

    private static void onMain(final Runnable action) throws Exception {
        final FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        task.get();
    }
}
