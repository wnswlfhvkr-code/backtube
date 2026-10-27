package org.schabi.newpipe.info_list.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.util.external_communication.KoreUtils;

import java.lang.reflect.Field;
import java.util.List;

/** Checks the entries actually assembled for a stream's long-press dialog. */
public class OnlineVideoLongPressMenuTest {
    @Test
    public void onlineVideoKeepsOtherActionsAndOffersOneOfflineSave() throws Exception {
        final List<Integer> entries = entries(StreamType.VIDEO_STREAM,
                "https://example.invalid/watch?v=selected");

        assertEquals(1, count(entries, R.string.offline_save));
        assertFalse(entries.contains(R.string.start_here_on_background));
        assertFalse(entries.contains(R.string.start_here_on_popup));
        assertFalse(entries.contains(R.string.open_in_browser));
        assertTrue(entries.contains(R.string.download));
        assertTrue(entries.contains(R.string.add_to_playlist));
        assertTrue(entries.contains(R.string.share));
        assertTrue(entries.contains(R.string.show_channel_details));
    }

    @Test
    public void audioAndLocalItemsKeepTheirExistingMenus() throws Exception {
        final List<Integer> audio = entries(StreamType.AUDIO_STREAM,
                "https://example.invalid/audio");
        assertTrue(audio.contains(R.string.start_here_on_background));
        assertTrue(audio.contains(R.string.open_in_browser));
        assertFalse(audio.contains(R.string.offline_save));

        final List<Integer> local = entries(StreamType.VIDEO_STREAM, "backtube-offline:123");
        assertTrue(local.contains(R.string.start_here_on_background));
        assertTrue(local.contains(R.string.start_here_on_popup));
        assertTrue(local.contains(R.string.open_in_browser));
        assertFalse(local.contains(R.string.offline_save));
    }

    private static int count(final List<Integer> entries, final int resource) {
        return (int) entries.stream().filter(value -> value == resource).count();
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> entries(final StreamType type, final String url)
            throws Exception {
        final Activity activity = mock(Activity.class);
        final Context context = mock(Context.class);
        when(context.getResources()).thenReturn(mock(Resources.class));
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final StreamInfoItem item = new StreamInfoItem(1, url, "Selected", type);
        final PlayerHolder holder = mock(PlayerHolder.class);
        try (MockedStatic<PlayerHolder> players = mockStatic(PlayerHolder.class);
             MockedStatic<PreferenceManager> preference = mockStatic(PreferenceManager.class);
             MockedStatic<KoreUtils> kodi = mockStatic(KoreUtils.class)) {
            players.when(PlayerHolder::getInstance).thenReturn(holder);
            preference.when(() -> PreferenceManager.getDefaultSharedPreferences(context))
                    .thenReturn(preferences);
            final InfoItemDialog.Builder builder = new InfoItemDialog.Builder(activity, context,
                    mock(Fragment.class), item, false)
                    .addDefaultBeginningEntries()
                    .addDefaultEndEntries();
            final Field field = InfoItemDialog.Builder.class.getDeclaredField("entries");
            field.setAccessible(true);
            return ((List<StreamDialogEntry>) field.get(builder)).stream()
                    .map(entry -> entry.resource).toList();
        }
    }
}
