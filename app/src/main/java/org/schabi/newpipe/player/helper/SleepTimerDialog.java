package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.text.InputType;
import android.text.method.TextKeyListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.schabi.newpipe.R;
import org.schabi.newpipe.player.Player;

/** Shared timer controls for one session. Hosts dismiss on disconnect or destruction. */
public final class SleepTimerDialog {
    private final Context context;
    private final Player player;
    private final Runnable onChanged;
    @Nullable
    private AlertDialog currentDialog;

    public SleepTimerDialog(final Context context, final Player player, final Runnable onChanged) {
        this.context = context;
        this.player = player;
        this.onChanged = onChanged;
    }

    public void dismiss() {
        if (currentDialog != null) {
            currentDialog.dismiss();
            currentDialog = null;
        }
    }

    public static void updateButton(final TextView button, @Nullable final Player player) {
        button.setEnabled(player != null);
        if (player != null && player.isSleepTimerAtEndOfItem()) {
            button.setText(R.string.personal_sleep_timer_end_of_item);
        } else {
            final long remainingMillis = player == null ? 0 : player.getSleepTimerRemainingMillis();
            if (remainingMillis <= 0) {
                button.setText(R.string.personal_sleep_timer_off);
            } else {
                button.setText(button.getContext().getString(
                        R.string.personal_sleep_timer_remaining,
                        (remainingMillis + 59999) / 60000));
            }
        }
        button.setContentDescription(button.getContext().getString(
                R.string.personal_sleep_timer_title) + ", " + button.getText());
    }

    public void show() {
        dismiss();

        final int[] minutes = {15, 30, 60, 90, 120};
        final CharSequence[] options = new CharSequence[minutes.length + 4];
        for (int index = 0; index < minutes.length; index++) {
            options[index + 1] = context.getString(
                    R.string.personal_sleep_timer_minutes, minutes[index]);
        }
        final int customIndex = 0;
        final int endOfItemIndex = minutes.length + 1;
        final int extendIndex = endOfItemIndex + 1;
        final int cancelIndex = extendIndex + 1;
        options[customIndex] = context.getString(R.string.personal_sleep_timer_custom);
        options[endOfItemIndex] = context.getString(R.string.personal_sleep_timer_end_current_item);
        options[extendIndex] = context.getString(R.string.personal_sleep_timer_extend);
        options[cancelIndex] = context.getString(R.string.personal_sleep_timer_cancel_timer);

        final CheckBox fadeToggle = new CheckBox(context);
        fadeToggle.setText(R.string.personal_sleep_timer_fade);
        fadeToggle.setContentDescription(context.getString(R.string.personal_sleep_timer_fade));
        fadeToggle.setChecked(player.isSleepTimerFadeEnabled());
        fadeToggle.setOnCheckedChangeListener((buttonView, isChecked) ->
                player.setSleepTimerFadeEnabled(isChecked));

        final ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(context,
                android.R.layout.select_dialog_item, options) {
            @Override
            public boolean isEnabled(final int position) {
                return position != endOfItemIndex || player.canSetSleepTimerAtEndOfItem();
            }
        };
        final AlertDialog sleepTimerDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.personal_sleep_timer_title)
                .setView(fadeToggle)
                .setAdapter(adapter, (dialog, which) -> {
                    if (currentDialog != dialog) {
                        return;
                    }
                    if (which == customIndex) {
                        showCustomSleepTimerDialog();
                        return;
                    } else if (which <= minutes.length) {
                        player.setSleepTimer(minutes[which - 1] * 60_000L);
                    } else if (which == endOfItemIndex) {
                        if (!player.canSetSleepTimerAtEndOfItem()) {
                            return;
                        }
                        player.setSleepTimerAtEndOfItem();
                    } else if (which == extendIndex) {
                        player.extendSleepTimer();
                    } else {
                        player.cancelSleepTimer();
                    }
                    onChanged.run();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        sleepTimerDialog.setOnShowListener(ignored -> limitSleepTimerListHeight(sleepTimerDialog));
        currentDialog = sleepTimerDialog;
        sleepTimerDialog.show();
    }

    private void limitSleepTimerListHeight(final AlertDialog dialog) {
        final ListView list = dialog.getListView();
        if (list == null) {
            return;
        }

        final ViewGroup.LayoutParams parameters = list.getLayoutParams();
        final var metrics = context.getResources().getDisplayMetrics();
        // Leave room for the title, fade checkbox, buttons and system bars on short screens.
        final int maximumHeight = Math.max((int) (64 * metrics.density), Math.min(
                (int) (metrics.heightPixels * 0.45f),
                metrics.heightPixels - (int) (272 * metrics.density)));
        final int currentHeight = list.getHeight() > 0 ? list.getHeight() : maximumHeight;
        parameters.height = Math.min(currentHeight, maximumHeight);
        list.setLayoutParams(parameters);
        list.requestLayout();
    }

    private void showCustomSleepTimerDialog() {
        dismiss();
        final LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.HORIZONTAL);
        final int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
        fields.setPadding(padding, padding / 2, padding, padding / 2);
        final EditText hours = durationField(R.id.timer_duration_hours,
                R.string.personal_timer_hours, "0", EditorInfo.IME_ACTION_NEXT);
        final EditText minutes = durationField(R.id.timer_duration_minutes,
                R.string.personal_timer_minutes, "30", EditorInfo.IME_ACTION_DONE);
        addDurationField(fields, hours, R.string.personal_timer_hours);
        addDurationField(fields, minutes, R.string.personal_timer_minutes);
        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(fields);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.personal_sleep_timer_custom)
                .setView(scroll)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (currentDialog != dialog) {
                    return;
                }
                final long duration;
                try {
                    duration = parseDurationMillis(hours.getText().toString(),
                            minutes.getText().toString());
                } catch (final IllegalArgumentException error) {
                    minutes.setError(context.getString(R.string.personal_timer_invalid_duration));
                    return;
                }
                player.setSleepTimer(duration);
                onChanged.run();
                dismiss();
            });
            hours.requestFocus();
        });
        currentDialog = dialog;
        dialog.show();
    }

    private EditText durationField(final int id, final int label, final String value,
                                   final int imeAction) {
        final EditText field = new EditText(context);
        field.setId(id);
        field.setSingleLine(true);
        // Request a numeric keyboard without a digits filter that silently changes invalid paste.
        field.setKeyListener(TextKeyListener.getInstance());
        field.setRawInputType(InputType.TYPE_CLASS_NUMBER);
        field.setImeOptions(imeAction);
        field.setSelectAllOnFocus(true);
        field.setMinEms(2);
        field.setText(value);
        field.setContentDescription(context.getString(label));
        return field;
    }

    private void addDurationField(final LinearLayout row, final EditText input, final int unit) {
        row.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView label = new TextView(context);
        label.setText(unit);
        row.addView(label);
    }

    static long parseDurationMillis(final String hours, final String minutes) {
        final long hourValue = durationNumber(hours);
        final long minuteValue = durationNumber(minutes);
        if (hourValue > 24 || minuteValue > 59) {
            throw new IllegalArgumentException("Duration out of range");
        }
        final long total = hourValue * 60 + minuteValue;
        if (total < 1 || total > 1440) {
            throw new IllegalArgumentException("Duration out of range");
        }
        return total * 60_000;
    }

    private static long durationNumber(final String text) {
        final String value = text.trim();
        if (!value.matches("[0-9]*")) {
            throw new IllegalArgumentException("Invalid duration");
        }
        return value.isEmpty() ? 0 : Long.parseLong(value);
    }
}
