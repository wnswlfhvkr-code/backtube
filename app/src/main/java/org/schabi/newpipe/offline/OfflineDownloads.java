package org.schabi.newpipe.offline;

import android.content.Context;
import org.schabi.newpipe.download.DownloadNetworkPolicy;

import us.shandian.giga.get.Mission;

/** Identifies app-owned downloads and connected transports eligible for queueing. */
public final class OfflineDownloads {
    private OfflineDownloads() { }

    public static boolean isManaged(final Mission mission) {
        return mission.storage != null && mission.storage.getTag() != null
                && mission.storage.getTag().startsWith("offline:");
    }

    public static String id(final Mission mission) {
        return mission.storage.getTag().substring("offline:".length());
    }

    public static boolean networkAllowed(final Context context) {
        final DownloadNetworkPolicy.Type type = DownloadNetworkPolicy.current(context);
        return type == DownloadNetworkPolicy.Type.WIFI
                || type == DownloadNetworkPolicy.Type.MOBILE;
    }
}
