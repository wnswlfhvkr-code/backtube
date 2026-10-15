package org.schabi.newpipe.error.autoreport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the explicit, extra-free broadcast sent by {@link AppFaultScheduler#signal(Context)}
 * after a sanitized fault has been recorded.
 *
 * <p>The receiver is not exported, declares no intent filter and runs in the default main
 * process, so WorkManager is never initialized in the ACRA sender process. All storage and
 * WorkManager access happens on the scheduler's background thread; the broadcast is kept alive
 * with {@link #goAsync()} until the request has completed.</p>
 */
public final class AppFaultEventReceiver extends BroadcastReceiver {

    /**
     * Requests rescheduling of fault delivery from the persisted outbox. Ignores the intent
     * contents, never logs and never throws.
     * @param context the context in which the receiver is running
     * @param intent the received explicit broadcast; its contents are ignored
     */
    @Override
    public void onReceive(final Context context, final Intent intent) {
        PendingResult pending = null;
        try {
            pending = goAsync();
            final PendingResult result = pending;
            AppFaultScheduler.request(context, result == null ? null : result::finish);
        } catch (final RuntimeException ignored) {
            finishQuietly(pending);
        }
    }

    private static void finishQuietly(final PendingResult pending) {
        if (pending == null) {
            return;
        }
        try {
            pending.finish();
        } catch (final RuntimeException ignored) {
            // Best effort only.
        }
    }
}
