package org.schabi.newpipe.util;

import android.content.Context;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

public final class DataSaver {
    public static final String LOW = "low";
    public static final String BALANCED = "balanced";
    public static final String HIGH = "high";

    private DataSaver() {
    }

    public static boolean isEnabled(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.data_saver_key), false);
    }

    public static boolean isMeteredSavingsActive(final Context context) {
        return isEnabled(context) && ListHelper.isMeteredNetwork(context);
    }

    public static String getAudioQuality(final Context context) {
        if (isMeteredSavingsActive(context) || ListHelper.isLimitingDataUsage(context)) {
            return LOW;
        }
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.audio_quality_key), BALANCED);
    }

    public static int getQualityLabel(final Context context) {
        final String quality = getAudioQuality(context);
        if (LOW.equals(quality)) {
            return R.string.audio_quality_low;
        }
        return HIGH.equals(quality) ? R.string.audio_quality_high : R.string.audio_quality_balanced;
    }
}
