package org.schabi.newpipe;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.fragment.app.FragmentManager;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.util.NavigationHelper;

import java.lang.reflect.Method;

/** Intent navigation must use the Activity owner of pending saved playback requests. */
public class DetailEntryOwnershipTest {
    @Test
    public void streamIntentUsesActivityAsNavigationOwner() throws Exception {
        final MainActivity activity = mock(MainActivity.class, CALLS_REAL_METHODS);
        final Context application = mock(Context.class);
        final FragmentManager fragments = mock(FragmentManager.class);
        final Intent intent = mock(Intent.class);
        doReturn(application).when(activity).getApplicationContext();
        doReturn(fragments).when(activity).getSupportFragmentManager();
        when(intent.hasExtra(Constants.KEY_LINK_TYPE)).thenReturn(true);
        when(intent.getSerializableExtra(Constants.KEY_LINK_TYPE))
                .thenReturn(StreamingService.LinkType.STREAM);
        when(intent.getStringExtra(Constants.KEY_URL)).thenReturn("https://example.test/a");
        when(intent.getStringExtra(Constants.KEY_TITLE)).thenReturn("A");
        when(intent.getIntExtra(Constants.KEY_SERVICE_ID, 0)).thenReturn(1);

        try (MockedStatic<Log> ignored = mockStatic(Log.class);
             MockedStatic<NavigationHelper> navigation = mockStatic(NavigationHelper.class)) {
            final Method handler = MainActivity.class.getDeclaredMethod(
                    "handleIntent", Intent.class);
            handler.setAccessible(true);
            handler.invoke(activity, intent);
            navigation.verify(() -> NavigationHelper.openVideoDetailFragment(
                    same(activity), same(fragments), eq(1), eq("https://example.test/a"),
                    eq("A"), isNull(), eq(false), eq(false)));
        }
    }
}
