package org.schabi.newpipe.error;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.jakewharton.rxbinding4.view.RxView;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.ktx.ViewUtils;
import org.schabi.newpipe.util.text.TextViewExtensionsKt;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Real reusable panel/action policy; Android views, text spans and animation are boundaries. */
public class RetiredTrendingErrorPanelTest {
    @Test public void panelRestoresNormalActionsAfterShowingRetiredFeature() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(supplier -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        try (MockedStatic<RxView> clicks = mockStatic(RxView.class);
             MockedStatic<ViewUtils> animation = mockStatic(ViewUtils.class);
             MockedStatic<TextViewExtensionsKt> spans = mockStatic(TextViewExtensionsKt.class);
             MockedStatic<ContextCompat> contexts = mockStatic(ContextCompat.class)) {
            clicks.when(() -> RxView.clicks(any())).thenReturn(Observable.never());
            final Context context = mock(Context.class);
            contexts.when(() -> ContextCompat.getContextForLanguage(context)).thenReturn(context);
            when(context.getText(anyInt())).thenReturn("Generated message");
            final View root = mock(View.class);
            final View panel = mock(View.class);
            when(root.getContext()).thenReturn(context);
            when(root.findViewById(R.id.error_panel)).thenReturn(panel);
            final TextView message = mock(TextView.class);
            when(panel.findViewById(R.id.error_message_view)).thenReturn(message);
            when(panel.findViewById(R.id.error_message_service_info_view))
                    .thenReturn(mock(TextView.class));
            when(panel.findViewById(R.id.error_message_service_explanation_view))
                    .thenReturn(mock(TextView.class));
            final Button report = mock(Button.class);
            final Button retry = mock(Button.class);
            final Button browser = mock(Button.class);
            when(panel.findViewById(R.id.error_action_button)).thenReturn(report);
            when(panel.findViewById(R.id.error_retry_button)).thenReturn(retry);
            when(panel.findViewById(R.id.error_open_in_browser)).thenReturn(browser);
            final ErrorPanelHelper helper = new ErrorPanelHelper(mock(Fragment.class), root,
                    () -> { });
            final String url = "https://www.youtube.com/feed/trending";
            final ErrorInfo retired = new ErrorInfo(new YoutubeTrendingUnavailableException(),
                    UserAction.REQUESTED_KIOSK, "Generated public route", 0, url);
            helper.showError(retired);
            verify(context).getText(R.string.youtube_trending_unavailable);
            for (final Button action : new Button[]{report, retry, browser}) {
                verify(action).setVisibility(View.GONE);
                verify(action, never()).setVisibility(View.VISIBLE);
            }
            clearInvocations(report, retry, browser);
            helper.showError(new ErrorInfo(new ParsingException("Generated parse failure"),
                    UserAction.REQUESTED_KIOSK, "Generated different route", 0, url));
            for (final Button action : new Button[]{report, retry, browser}) {
                verify(action).setVisibility(View.VISIBLE);
            }
            clearInvocations(report, retry, browser);
            helper.showError(retired);
            for (final Button action : new Button[]{report, retry, browser}) {
                verify(action).setVisibility(View.GONE);
                verify(action, never()).setVisibility(View.VISIBLE);
            }
            helper.dispose();
        } finally {
            RxAndroidPlugins.reset();
        }
    }
}
