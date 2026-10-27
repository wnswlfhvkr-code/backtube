package org.schabi.newpipe.download;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

/** Classifies the active transport for an individual download request. */
public final class DownloadNetworkPolicy {
    public enum Type { NONE, WIFI, MOBILE, OTHER }

    private DownloadNetworkPolicy() { }

    public static Type current(final Context context) {
        final ConnectivityManager manager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return Type.NONE;
        }
        final NetworkInfo info = manager.getActiveNetworkInfo();
        if (info == null || !info.isConnected()) {
            return Type.NONE;
        }
        final NetworkCapabilities capabilities = manager.getNetworkCapabilities(
                manager.getActiveNetwork());
        if (capabilities == null) {
            return Type.OTHER;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return Type.WIFI;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return Type.MOBILE;
        }
        return Type.OTHER;
    }
}
