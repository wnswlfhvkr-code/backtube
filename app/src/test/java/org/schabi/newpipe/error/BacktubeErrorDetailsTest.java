package org.schabi.newpipe.error;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.text.Editable;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

import com.grack.nanojson.JsonParser;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.ActivityErrorBinding;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.external_communication.ShareUtils;
import org.schabi.newpipe.views.NewPipeEditText;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Real local copy callback, share menu and generated error text; Android delivery is mocked. */
public class BacktubeErrorDetailsTest {
    @Test public void copyAndUserChosenShareKeepTheGeneratedErrorAndCommentLocally()
            throws Exception {
        final ErrorActivity activity = mock(ErrorActivity.class);
        doCallRealMethod().when(activity).onOptionsItemSelected(any(MenuItem.class));
        when(activity.getPackageName()).thenReturn("org.schabi.newpipe");
        when(activity.getString(anyInt())).thenReturn("Error details");
        final Context application = mock(Context.class);
        when(activity.getApplicationContext()).thenReturn(application);
        final ActivityErrorBinding binding = mock(ActivityErrorBinding.class);
        final Button copy = mock(Button.class);
        final AtomicReference<View.OnClickListener> copyAction = new AtomicReference<>();
        doAnswer(call -> {
            copyAction.set(call.getArgument(0));
            return null;
        }).when(copy).setOnClickListener(any());
        final NewPipeEditText comment = mock(NewPipeEditText.class);
        final Editable text = mock(Editable.class);
        when(text.toString()).thenReturn("Generated local comment");
        when(comment.getText()).thenReturn(text);
        set(binding, "errorReportCopyButton", copy);
        set(binding, "errorCommentBox", comment);
        set(activity, "binding", binding);
        set(activity, "errorInfo", new ErrorInfo(new IllegalStateException("Generated failure"),
                UserAction.USER_REPORT, "Generated local request", 0));
        set(activity, "currentTimeStamp", "2026-10-16T00:00:00Z");
        final List<String> copied = new ArrayList<>();
        final List<String> shared = new ArrayList<>();
        final List<String> destinations = new ArrayList<>();
        try (MockedStatic<Localization> locales = mockStatic(Localization.class);
             MockedStatic<ShareUtils> actions = mockStatic(ShareUtils.class)) {
            locales.when(() -> Localization.getPreferredContentCountry(activity)).thenReturn(
                    new org.schabi.newpipe.extractor.localization.ContentCountry("US"));
            locales.when(() -> Localization.getPreferredLocalization(activity)).thenReturn(
                    new org.schabi.newpipe.extractor.localization.Localization("en", "US"));
            locales.when(Localization::getAppLocale).thenReturn(Locale.US);
            actions.when(() -> ShareUtils.copyToClipboard(any(Context.class), anyString()))
                    .thenAnswer(call -> {
                        copied.add(call.getArgument(1));
                        return null;
                    });
            actions.when(() -> ShareUtils.shareText(any(Context.class), anyString(), anyString()))
                    .thenAnswer(call -> {
                        shared.add(call.getArgument(2));
                        return null;
                    });
            actions.when(() -> ShareUtils.openUrlInApp(any(Context.class), anyString()))
                    .thenAnswer(call -> {
                        destinations.add(call.getArgument(1));
                        return null;
                    });
            final var setup = ErrorActivity.class.getDeclaredMethod("setupErrorActions");
            setup.setAccessible(true);
            setup.invoke(activity);
            copyAction.get().onClick(copy);
            assertEquals(1, copied.size());
            assertTrue(copied.get(0).contains("Generated local comment"));
            assertTrue(copied.get(0).contains("Generated local request"));
            assertTrue(copied.get(0).contains("Generated failure"));
            final MenuItem share = mock(MenuItem.class);
            when(share.getItemId()).thenReturn(R.id.menu_item_share_error);
            assertTrue(activity.onOptionsItemSelected(share));
            assertEquals(1, shared.size());
            final var json = JsonParser.object().from(shared.get(0));
            assertEquals("Generated local comment", json.getString("user_comment"));
            assertEquals("Generated local request", json.getString("request"));
            assertTrue(destinations.isEmpty());
        }
    }

    private static void set(final Object owner, final String name, final Object value)
            throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
