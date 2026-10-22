package org.schabi.newpipe.offline;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.PendingIntentCompat;
import androidx.core.content.ContextCompat;

import org.schabi.newpipe.R;

import java.io.IOException;

/** A completed offline save opens the same shelf available from the main drawer. */
public final class OfflineSaveNotifications {
    private OfflineSaveNotifications() {
    }

    public static void showSaved(final Context context, final String title) {
        final NotificationManagerCompat notifications = NotificationManagerCompat.from(context);
        if (!notifications.areNotificationsEnabled()
                || (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            return;
        }
        try {
            notifications.notify("offline_saved", 0, build(context, title));
        } catch (final SecurityException ignored) {
            // A permission change must not turn a successfully saved copy into a failed save.
        }
    }

    public static void showSavedEntry(final Context context, final String id) {
        try {
            final OfflineStore.Entry entry = OfflineLibrary.get(context).store().get(id);
            if (entry != null && entry.state == OfflineStore.State.READY) {
                showSaved(context, entry.title);
            }
        } catch (final IOException ignored) {
            // The shelf remains available even if completion notification metadata cannot be read.
        }
    }

    static Notification build(final Context context, final String title) {
        final Intent intent = new Intent(context, OfflineLibraryActivity.class)
                .setAction(context.getPackageName() + ".OPEN_OFFLINE_LIBRARY")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final PendingIntent openList = PendingIntentCompat.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT, false);
        return new NotificationCompat.Builder(context, context.getString(
                R.string.notification_channel_id))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.offline_saved_complete))
                .setContentText(title)
                .setContentIntent(openList)
                .addAction(android.R.drawable.ic_menu_view,
                        context.getString(R.string.offline_open_list), openList)
                .setAutoCancel(true)
                .build();
    }
}
