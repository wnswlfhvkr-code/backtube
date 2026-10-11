package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import org.junit.Test;

public class SelectedTabPreferenceTest {
    @Test
    public void missingPreferenceSelectsRelated() {
        assertEquals("NEXT VIDEO", readSelectedTab(null));
    }

    @Test
    public void savedCommentsRemainSelected() {
        assertEquals("COMMENTS", readSelectedTab("COMMENTS"));
    }

    @Test
    public void savedRelatedRemainSelected() {
        assertEquals("NEXT VIDEO", readSelectedTab("NEXT VIDEO"));
    }

    @Test
    public void savedDescriptionRemainsSelected() {
        assertEquals("DESCRIPTION TAB", readSelectedTab("DESCRIPTION TAB"));
    }

    private String readSelectedTab(final String stored) {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final String key = "stream_info_selected_tab";
        when(preferences.getString(eq(key), anyString())).thenAnswer(call ->
                stored == null ? call.<String>getArgument(1) : stored);
        return VideoDetailFragment.readSelectedTab(preferences, key);
    }
}
