package org.schabi.newpipe.offline;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import androidx.preference.PreferenceManager;

import us.shandian.giga.get.Mission;

/** Policy for app-owned Giga outputs; ordinary download behavior is unchanged. */
public final class OfflineDownloads {
    public static final String WIFI_ONLY = "offline_wifi_only";
    private OfflineDownloads() { }

    public static boolean isManaged(final Mission mission) {
        return mission.storage != null && mission.storage.getTag() != null
                && mission.storage.getTag().startsWith("offline:");
    }

    public static String id(final Mission mission) {
        return mission.storage.getTag().substring("offline:".length());
    }

    public static boolean networkAllowed(final Context context) {
        final ConnectivityManager manager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        final NetworkCapabilities capabilities = manager == null ? null
                : manager.getNetworkCapabilities(manager.getActiveNetwork());
        if (capabilities == null || !capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return false;
        }
        return !PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(WIFI_ONLY, true)
                || (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }
}
