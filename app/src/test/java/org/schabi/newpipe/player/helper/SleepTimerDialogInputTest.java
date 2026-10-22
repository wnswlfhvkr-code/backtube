package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.text.Editable;
import android.text.InputType;
import android.text.method.TextKeyListener;
import android.util.DisplayMetrics;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.player.Player;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Production parsing and apply callback; Android rendering is a separate boundary. */
public class SleepTimerDialogInputTest {
    @Test public void customDurationOffersTwoKeyboardFieldsInsteadOfWheel() throws Exception {
        try (DialogFixture fixture = new DialogFixture()) {
            fixture.show();
            assertEquals(2, fixture.fields.constructed().size());
            assertEquals(0, fixture.wheels.constructed().size());
            for (final EditText field : fixture.fields.constructed()) {
                verify(field).setRawInputType(InputType.TYPE_CLASS_NUMBER);
            }
        }
    }

    @Test public void invalidApplyKeepsDialogOpenAndNeverCallsTimer() throws Exception {
        try (DialogFixture fixture = new DialogFixture()) {
            fixture.show();
            for (final String[] invalid : new String[][]{{"", ""}, {"0", "0"},
                    {"-1", "30"}, {"1a", "0"}, {"0", "100"}, {"24", "1"},
                    {"9999999999999999999999999999", "0"}}) {
                fixture.fields.constructed().get(0).setText(invalid[0]);
                fixture.fields.constructed().get(1).setText(invalid[1]);
                fixture.apply.get().onClick(fixture.button);
            }
            verify(fixture.player, never()).setSleepTimer(org.mockito.ArgumentMatchers.anyLong());
            verify(fixture.dialog, never()).dismiss();
            assertEquals(0, fixture.changes.get());
            fixture.fields.constructed().get(0).setText("1");
            fixture.fields.constructed().get(1).setText("");
            fixture.apply.get().onClick(fixture.button);
            verify(fixture.player).setSleepTimer(3_600_000L);
            verify(fixture.dialog).dismiss();
            assertEquals(1, fixture.changes.get());
        }
    }

    @Test public void parsesPartialDurationAndMaximumWithoutClockSemantics() {
        assertEquals(30 * 60_000L, SleepTimerDialog.parseDurationMillis("", "30"));
        assertEquals(60 * 60_000L, SleepTimerDialog.parseDurationMillis("1", ""));
        assertEquals(119 * 60_000L, SleepTimerDialog.parseDurationMillis("1", "59"));
        assertEquals(86_400_000L, SleepTimerDialog.parseDurationMillis("24", "0"));
        for (final String[] invalid : new String[][]{{"", ""}, {"0", "0"}, {"25", "0"},
                {"24", "1"}, {"0", "60"}, {"0", "100"}, {"-1", "0"}, {"1a", "0"},
                {"0", "-1"}, {"0", "1a"}, {"9223372036854775808", "0"}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SleepTimerDialog.parseDurationMillis(invalid[0], invalid[1]));
        }
    }

    private static final class DialogFixture implements AutoCloseable {
        private final java.util.concurrent.atomic.AtomicInteger changes =
                new java.util.concurrent.atomic.AtomicInteger();
        private final Context context = mock(Context.class);
        private final Player player = mock(Player.class);
        private final AlertDialog dialog = mock(AlertDialog.class);
        private final Button button = mock(Button.class);
        private final AtomicReference<android.view.View.OnClickListener> apply =
                new AtomicReference<>();
        private final AtomicReference<DialogInterface.OnShowListener> shown =
                new AtomicReference<>();
        private final MockedStatic<TextKeyListener> keys = mockStatic(TextKeyListener.class);
        private final MockedConstruction<LinearLayout> rows = mockConstruction(LinearLayout.class);
        private final MockedConstruction<LinearLayout.LayoutParams> params =
                mockConstruction(LinearLayout.LayoutParams.class);
        private final MockedConstruction<ScrollView> scrolls = mockConstruction(ScrollView.class);
        private final MockedConstruction<TextView> labels = mockConstruction(TextView.class);
        private final MockedConstruction<NumberPicker> wheels =
                mockConstruction(NumberPicker.class);
        private final MockedConstruction<EditText> fields = mockConstruction(EditText.class,
                (field, setup) -> {
                    final AtomicReference<String> value = new AtomicReference<>("");
                    final Editable text = mock(Editable.class);
                    when(text.toString()).thenAnswer(call -> value.get());
                    when(field.getText()).thenReturn(text);
                    doAnswer(call -> {
                        value.set(call.getArgument(0).toString());
                        return null;
                    }).when(field).setText(any(CharSequence.class));
                });
        private final MockedConstruction<AlertDialog.Builder> builders = mockConstruction(
                AlertDialog.Builder.class, withSettings().defaultAnswer(RETURNS_SELF),
                (builder, setup) -> when(builder.create()).thenReturn(dialog));

        private DialogFixture() {
            final Resources resources = mock(Resources.class);
            when(context.getResources()).thenReturn(resources);
            when(resources.getDisplayMetrics()).thenReturn(mock(DisplayMetrics.class));
            when(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).thenReturn(button);
            doAnswer(call -> {
                shown.set(call.getArgument(0));
                return null;
            }).when(dialog).setOnShowListener(any());
            doAnswer(call -> {
                shown.get().onShow(dialog);
                return null;
            }).when(dialog).show();
            doAnswer(call -> {
                apply.set(call.getArgument(0));
                return null;
            }).when(button).setOnClickListener(any());
        }

        private void show() throws Exception {
            final SleepTimerDialog timer = new SleepTimerDialog(
                    context, player, changes::incrementAndGet);
            final var custom = SleepTimerDialog.class.getDeclaredMethod(
                    "showCustomSleepTimerDialog");
            custom.setAccessible(true);
            custom.invoke(timer);
        }

        @Override public void close() {
            builders.close();
            fields.close();
            wheels.close();
            labels.close();
            scrolls.close();
            params.close();
            rows.close();
            keys.close();
        }
    }
}
