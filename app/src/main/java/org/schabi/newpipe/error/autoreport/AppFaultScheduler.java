package org.schabi.newpipe.error.autoreport;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.WorkManager;

import org.schabi.newpipe.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Schedules at most one unfinished, network-constrained, one-time delivery job for sanitized
 * app faults.
 *
 * <p>Delivery is off unless the build-time relay endpoint is valid. When it is off, or when the
 * persisted outbox holds nothing sendable, no job is kept. There is no periodic work, no
 * polling, no alarm and no service; every job is a single one-time request whose initial delay
 * is derived from the persisted outbox state. Lost or replaced jobs never lose a report,
 * because claims, leases and attempt receipts are persisted by {@link AppFaultOutbox}.</p>
 */
public final class AppFaultScheduler {
    /** Unique WorkManager name of the single fault delivery job. */
    public static final String WORK_NAME = "sanitized-app-fault-delivery";

    /** Directory below {@link Context#getNoBackupFilesDir()} holding the outbox. */
    static final String OUTBOX_DIRECTORY = "sanitized-app-faults";

    private static final String THREAD_NAME = "app-fault-scheduler";
    private static final long OPERATION_TIMEOUT_SECONDS = 30L;
    private static final long NONE = -1L;

    /**
     * Serializes every snapshot of the persisted outbox together with the WorkManager
     * operation derived from it, for both event scheduling and worker successors.
     */
    private static final Object LOCK = new Object();

    private AppFaultScheduler() {
    }

    /**
     * Replaces the unique delivery job according to the persisted outbox state, or cancels it
     * when delivery is off or nothing is sendable. Blocks until WorkManager has applied the
     * operation, so it must only be called from a background thread or from synchronous
     * instrumentation.
     * @param context any context; only its application context is used
     * @param endpoint the relay endpoint, possibly {@code null} or empty
     * @param outbox the persisted fault outbox, possibly {@code null}
     * @param now current wall clock time in milliseconds
     * @throws IOException if local fault storage cannot be read or the operation fails
     */
    public static void schedule(@NonNull final Context context, @Nullable final String endpoint,
                                @Nullable final AppFaultOutbox outbox, final long now)
            throws IOException {
        update(context.getApplicationContext(), endpoint, outbox, now,
                ExistingWorkPolicy.REPLACE, true);
    }

    /**
     * Starts a bounded, short-lived background thread that reschedules delivery from the
     * persisted outbox. Never touches storage or WorkManager on the calling thread, never logs
     * and always runs {@code finished} exactly once.
     * @param context any context; only its application context is used
     * @param finished callback run when the request completed, possibly {@code null}
     */
    public static void request(@NonNull final Context context,
                               @Nullable final Runnable finished) {
        final Context appContext = context.getApplicationContext();
        final Thread thread = new Thread(() -> {
            try {
                scheduleDefault(appContext);
            } catch (final IOException | RuntimeException ignored) {
                // Fail closed: delivery is best effort and must never affect the app.
            } finally {
                runQuietly(finished);
            }
        }, THREAD_NAME);
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (final RuntimeException e) {
            runQuietly(finished);
        }
    }

    /**
     * Notifies {@link AppFaultEventReceiver} that a fault may have been recorded. Sends an
     * explicit broadcast without extras, and only when delivery is on. Does not initialize
     * WorkManager, so it is safe to call from the crash reporting process.
     * @param context any context
     */
    public static void signal(@NonNull final Context context) {
        if (!AppFaultHttpProtocol.isEndpointConfigured(BuildConfig.APP_FAULT_ENDPOINT)) {
            return;
        }
        try {
            context.sendBroadcast(new Intent(context, AppFaultEventReceiver.class));
        } catch (final RuntimeException ignored) {
            // Best effort only.
        }
    }

    /**
     * Opens the persisted outbox in the no-backup files directory.
     * @param context application context
     * @return the outbox
     */
    static AppFaultOutbox openOutbox(@NonNull final Context context) {
        return new AppFaultOutbox(new File(context.getNoBackupFilesDir(), OUTBOX_DIRECTORY));
    }

    /**
     * Appends the successor job from the running worker so that it does not cancel itself.
     * Does nothing when no fault is sendable.
     * @param context application context
     * @param endpoint the relay endpoint
     * @param outbox the persisted fault outbox
     * @param now current wall clock time in milliseconds
     * @throws IOException if local fault storage cannot be read or the operation fails
     */
    static void scheduleSuccessor(@NonNull final Context context, final String endpoint,
                                  final AppFaultOutbox outbox, final long now)
            throws IOException {
        update(context, endpoint, outbox, now, ExistingWorkPolicy.APPEND_OR_REPLACE, false);
    }

    /**
     * Reschedules from the build-time endpoint. When delivery is off, the outbox is not read
     * and any old unique job is cancelled.
     * @param appContext application context
     * @throws IOException if local fault storage cannot be read or the operation fails
     */
    private static void scheduleDefault(final Context appContext) throws IOException {
        final String endpoint = BuildConfig.APP_FAULT_ENDPOINT;
        if (!AppFaultHttpProtocol.isEndpointConfigured(endpoint)) {
            schedule(appContext, endpoint, null, System.currentTimeMillis());
            return;
        }
        schedule(appContext, endpoint, openOutbox(appContext), System.currentTimeMillis());
    }

    private static void update(final Context context, final String endpoint,
                               final AppFaultOutbox outbox, final long now,
                               final ExistingWorkPolicy policy,
                               final boolean cancelWhenEmpty) throws IOException {
        synchronized (LOCK) {
            final long delay = AppFaultScheduling.delayUntilNext(outbox, endpoint, now);
            final WorkManager workManager = WorkManager.getInstance(context);
            if (delay > NONE) {
                enqueue(workManager, policy, delay);
                return;
            }
            if (!cancelWhenEmpty) {
                return;
            }
            await(workManager.cancelUniqueWork(WORK_NAME));
            if (outbox == null || !AppFaultHttpProtocol.isEndpointConfigured(endpoint)) {
                return;
            }
            // The outbox is not atomic with WorkManager: a fault may have been recorded and
            // scheduled after the empty snapshot, so recheck the persisted state after the
            // cancel and restore the job if something became sendable.
            final long recheck = AppFaultScheduling.delayUntilNext(outbox, endpoint, now);
            if (recheck > NONE) {
                enqueue(workManager, ExistingWorkPolicy.REPLACE, recheck);
            }
        }
    }

    /**
     * Enqueues the single one-time, network-constrained delivery job.
     * @param workManager the WorkManager instance
     * @param policy the unique work policy
     * @param delay initial delay in milliseconds
     * @throws IOException if the operation fails or times out
     */
    private static void enqueue(final WorkManager workManager, final ExistingWorkPolicy policy,
                                final long delay) throws IOException {
        final Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(AppFaultUploadWorker.class)
                        .setConstraints(constraints)
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .build();
        await(workManager.enqueueUniqueWork(WORK_NAME, policy, request));
    }

    private static void await(final Operation operation) throws IOException {
        try {
            operation.getResult().get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while scheduling");
        } catch (final ExecutionException | TimeoutException e) {
            throw new IOException("Scheduling failed");
        }
    }

    private static void runQuietly(@Nullable final Runnable runnable) {
        if (runnable == null) {
            return;
        }
        try {
            runnable.run();
        } catch (final RuntimeException ignored) {
            // The caller's completion callback must not crash the scheduler thread.
        }
    }
}
