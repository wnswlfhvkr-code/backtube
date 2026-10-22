package org.schabi.newpipe.error.autoreport;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.schabi.newpipe.BuildConfig;

import java.io.IOException;

/**
 * One-time worker performing at most one sanitized app fault delivery attempt.
 *
 * <p>Validates the build-time endpoint before touching storage, sends at most one fault, and,
 * unless stopped, appends a successor job computed from fresh persisted state. Storage or
 * corruption failures fail closed: the worker succeeds without rescheduling, never retries
 * through WorkManager and never logs.</p>
 */
public final class AppFaultUploadWorker extends Worker {
    private volatile HttpFaultTransport activeTransport;

    /**
     * Creates the worker.
     * @param context the application context
     * @param params the worker parameters
     */
    public AppFaultUploadWorker(@NonNull final Context context,
                                @NonNull final WorkerParameters params) {
        super(context, params);
    }

    /**
     * Performs at most one delivery attempt and schedules the successor.
     * @return always {@link Result#success()}
     */
    @NonNull
    @Override
    public Result doWork() {
        final String endpoint = BuildConfig.APP_FAULT_ENDPOINT;
        if (!AppFaultHttpProtocol.isEndpointConfigured(endpoint)) {
            return Result.success();
        }
        final Context context = getApplicationContext();
        try {
            final AppFaultOutbox outbox = AppFaultScheduler.openOutbox(context);
            final HttpFaultTransport transport = new HttpFaultTransport(endpoint);
            activeTransport = transport;
            try {
                if (!isStopped()) {
                    new AppFaultDelivery(outbox, transport, System::currentTimeMillis)
                            .deliverOne(System.currentTimeMillis());
                }
            } finally {
                activeTransport = null;
                closeQuietly(transport);
            }
            if (!isStopped()) {
                AppFaultScheduler.scheduleSuccessor(context, endpoint, outbox,
                        System.currentTimeMillis());
            }
        } catch (final IOException | RuntimeException ignored) {
            // Fail closed: do not repeat work against a broken local store.
        }
        return Result.success();
    }

    @Override
    public void onStopped() {
        super.onStopped();
        final HttpFaultTransport transport = activeTransport;
        if (transport != null) {
            closeQuietly(transport);
        }
    }

    private static void closeQuietly(final HttpFaultTransport transport) {
        try {
            transport.close();
        } catch (final RuntimeException ignored) {
            // Closing is best effort.
        }
    }
}
