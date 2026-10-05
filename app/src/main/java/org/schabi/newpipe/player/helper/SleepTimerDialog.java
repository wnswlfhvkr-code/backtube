package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.NumberPicker;
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
            options[index] = context.getString(
                    R.string.personal_sleep_timer_minutes, minutes[index]);
        }
        final int customIndex = minutes.length;
        final int endOfItemIndex = customIndex + 1;
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
                    if (which < minutes.length) {
                        player.setSleepTimer(minutes[which] * 60_000L);
                    } else if (which == customIndex) {
                        showCustomSleepTimerDialog();
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
        final NumberPicker minutesPicker = new NumberPicker(context);
        minutesPicker.setMinValue(1);
        minutesPicker.setMaxValue(1440);
        minutesPicker.setValue(30);
        minutesPicker.setWrapSelectorWheel(false);

        currentDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.personal_sleep_timer_custom)
                .setView(minutesPicker)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    if (currentDialog != dialog) {
                        return;
                    }
                    minutesPicker.clearFocus();
                    player.setSleepTimer(minutesPicker.getValue() * 60_000L);
                    onChanged.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

}
