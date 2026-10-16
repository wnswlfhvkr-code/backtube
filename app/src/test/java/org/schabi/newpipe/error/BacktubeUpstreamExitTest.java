package org.schabi.newpipe.error;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.MenuItem;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Actual drawer dispatch and error-message policy; no URL is opened or fetched. */
public class BacktubeUpstreamExitTest {
    @Test public void obsoleteDonationActionCannotOpenUpstreamWhileLocalRoutesRemain()
            throws Exception {
        final MainActivity activity = mock(MainActivity.class);
        final List<String> browserUrls = new ArrayList<>();
        final List<String> localRoutes = new ArrayList<>();
        when(activity.getString(R.string.donation_url)).thenReturn("https://newpipe.net/donate/");
        try (MockedStatic<ShareUtils> browsers = mockStatic(ShareUtils.class);
             MockedStatic<NavigationHelper> navigation = mockStatic(NavigationHelper.class)) {
            browsers.when(() -> ShareUtils.openUrlInBrowser(any(Context.class), anyString()))
                    .thenAnswer(call -> {
                        browserUrls.add(call.getArgument(1));
                        return null;
                    });
            navigation.when(() -> NavigationHelper.openSettings(activity)).thenAnswer(call -> {
                localRoutes.add("settings");
                return null;
            });
            navigation.when(() -> NavigationHelper.openAbout(activity)).thenAnswer(call -> {
                localRoutes.add("about");
                return null;
            });
            selectDrawerAction(activity, 1); // Historical donation action ID from the base build.
            assertTrue("an obsolete upstream donation action must have no external destination",
                    browserUrls.isEmpty());
            selectDrawerAction(activity, 0);
            selectDrawerAction(activity, 2);
            assertEquals(List.of("settings", "about"), localRoutes);
        }
    }

    @Test public void serviceIpBlockErrorKeepsLocalGuidanceWithoutAnUpstreamFaqDestination()
            throws Exception {
        final ErrorInfo.Companion.ErrorMessage message = ErrorInfo.Companion.getMessage(
                new SignInConfirmNotBotException("Generated service login requirement"),
                UserAction.REQUESTED_STREAM, ServiceList.YouTube.getServiceId());
        final Field formats = message.getClass().getDeclaredField("formatArgs");
        formats.setAccessible(true);
        final String[] arguments = (String[]) formats.get(message);
        assertTrue("IP-block guidance must not inject an upstream FAQ URL into rendered text",
                java.util.Arrays.stream(arguments).noneMatch(value ->
                        value.startsWith("http://") || value.startsWith("https://")));
        assertEquals("the affected service must remain in the local guidance",
                ServiceList.YouTube.getServiceInfo().getName(), arguments[0]);
    }

    private static void selectDrawerAction(final MainActivity activity, final int action)
            throws Exception {
        final MenuItem item = mock(MenuItem.class);
        when(item.getItemId()).thenReturn(action);
        final var route = MainActivity.class.getDeclaredMethod("optionsAboutSelected",
                MenuItem.class);
        route.setAccessible(true);
        route.invoke(activity, item);
    }
}
