package org.schabi.newpipe.error.autoreport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


/**
 * Bounded binary ledger of sanitized app faults awaiting delivery.
 *
 * <p>Version 2 adds per-fault delivery state: a persisted attempt counter, a claim generation,
 * an interrupted-claim lease and a retry deadline. Every claim is durably written before the
 * caller may start any network request, so a process death during delivery still counts as
 * an attempt. Version 1 ledgers are read and migrated; any malformed ledger fails closed.</p>
 */
public final class AppFaultOutbox {
    private static final int MAGIC_V1 = 0x41465031;
    private static final int MAGIC_V2 = 0x41465032;
    private static final int LIMIT = 8;
    private static final int MAX_ATTEMPTS = 3;
    private static final long DAY = 86400000L;
    private static final long LEASE = 15 * 60000L;
    private static final long MAX_CLOCK = Long.MAX_VALUE / 2;
    private final File directory;

    public enum Result { ADDED, DUPLICATE, RATE_LIMITED, FULL }

    /** Result of one delivery attempt, reported for a previously obtained claim. */
    public enum Outcome {
        /** Server confirmed a created or duplicate issue; stop sending this fault. */
        ACK,
        /** Transient failure; try again later if attempts remain. */
        RETRY,
        /** Do not send again, but keep the receipt until normal expiry. */
        RETAIN
    }

    /** A durable, leased permission to perform exactly one delivery attempt. */
    public static final class Claim {
        private final SanitizedAppFault fault;
        private final long generation;
        private final int attempts;

        private Claim(final SanitizedAppFault fault, final long generation,
                      final int attempts) {
            this.fault = fault;
            this.generation = generation;
            this.attempts = attempts;
        }

        public SanitizedAppFault fault() {
            return fault;
        }

        public long generation() {
            return generation;
        }

        public int attempts() {
            return attempts;
        }
    }

    public AppFaultOutbox(final File directory) {
        this.directory = directory;
    }

    public Result enqueue(final SanitizedAppFault fault, final long now) throws IOException {
        if (fault == null) {
            throw new IllegalArgumentException("Missing sanitized fault");
        }
        return access(now, state -> {
            if (state.entries.stream().anyMatch(entry -> entry.fault.equals(fault))) {
                return Result.DUPLICATE;
            }
            if (state.entries.size() >= LIMIT) {
                return Result.FULL;
            }
            if (state.entries.stream().filter(entry -> state.clock - entry.created < DAY)
                    .count() >= 3) {
                return Result.RATE_LIMITED;
            }
            state.entries.add(new Entry(fault, state.clock, true));
            return Result.ADDED;
        });
    }

    public List<SanitizedAppFault> pending(final long now) throws IOException {
        return access(now, state -> {
            final List<SanitizedAppFault> result = new ArrayList<>();
            for (final Entry entry : state.entries) {
                if (entry.pending) {
                    result.add(entry.fault);
                }
            }
            return result;
        });
    }

    public void acknowledge(final SanitizedAppFault fault, final long now) throws IOException {
        access(now, state -> {
            for (final Entry entry : state.entries) {
                if (entry.fault.equals(fault)) {
                    entry.pending = false;
                    entry.retained = false;
                    entry.leased = false;
                }
            }
            return null;
        });
    }

    /**
     * Durably claims one eligible pending fault, incrementing its attempt counter and taking a
     * 15-minute lease before returning. Interrupted claims are recovered first.
     * @param now current wall clock time in milliseconds
     * @return the durable claim, or empty when no fault is currently eligible
     */
    public Optional<Claim> claim(final long now) throws IOException {
        return access(now, state -> {
            final Entry entry = selectClaimable(state);
            if (entry == null) {
                return Optional.<Claim>empty();
            }
            entry.attempts++;
            entry.generation = ++state.sequence;
            entry.leased = true;
            entry.notBefore = state.clock + LEASE;
            return Optional.of(new Claim(entry.fault, entry.generation, entry.attempts));
        });
    }

    /**
     * Records the outcome of a claimed attempt. Returns {@code false} without changes when the
     * claim is stale (reclaimed, already completed, or no longer pending).
     * @param claim the claim previously obtained from {@link #claim(long)}
     * @param outcome the delivery outcome for the claimed attempt
     * @param now current wall clock time in milliseconds
     * @param retryAfterMillis server requested delay, or 0 when absent
     * @return {@code true} if the outcome was recorded, {@code false} for a stale claim
     */
    public boolean complete(final Claim claim, final Outcome outcome, final long now,
                            final long retryAfterMillis) throws IOException {
        if (claim == null || outcome == null) {
            throw new IllegalArgumentException("Missing claim outcome");
        }
        return access(now, state -> {
            for (final Entry entry : state.entries) {
                if (!entry.fault.equals(claim.fault)) {
                    continue;
                }
                if (!entry.pending || !entry.leased || entry.generation != claim.generation) {
                    return false;
                }
                entry.leased = false;
                switch (outcome) {
                    case ACK:
                        entry.pending = false;
                        break;
                    case RETAIN:
                        entry.retained = true;
                        break;
                    default:
                        entry.notBefore = state.clock
                                + retryDelay(entry.attempts, retryAfterMillis);
                        break;
                }
                return true;
            }
            return false;
        });
    }

    /**
     * Earliest time any fault may be claimed, or -1 if nothing remains sendable.
     * @param now current wall clock time in milliseconds
     * @return earliest eligible claim time in milliseconds, or -1 if nothing is sendable
     */
    public long nextEligibleAt(final long now) throws IOException {
        return access(now, state -> {
            long earliest = -1L;
            for (final Entry entry : state.entries) {
                if (isSendable(entry) && (earliest < 0 || entry.notBefore < earliest)) {
                    earliest = entry.notBefore;
                }
            }
            return earliest;
        });
    }

    private static boolean isSendable(final Entry entry) {
        return entry.pending && !entry.retained && entry.attempts < MAX_ATTEMPTS;
    }

    private static Entry selectClaimable(final State state) {
        Entry selected = null;
        for (final Entry entry : state.entries) {
            if (!isSendable(entry) || entry.notBefore > state.clock) {
                continue;
            }
            // Expired leases mark interrupted attempts; resolve those before fresh faults.
            if (selected == null || (entry.leased && !selected.leased)
                    || (entry.leased == selected.leased
                    && entry.notBefore < selected.notBefore)) {
                selected = entry;
            }
        }
        return selected;
    }

    private static long retryDelay(final int attempts, final long retryAfterMillis) {
        final long backoff = LEASE << Math.min(Math.max(attempts - 1, 0), 7);
        return Math.min(DAY, Math.max(backoff, Math.max(0L, retryAfterMillis)));
    }

    // Both same-process threads and ACRA's separate sender process share one transaction lock.
    private <T> T access(final long now, final Operation<T> operation) throws IOException {
        synchronized (AppFaultOutbox.class) {
            if (now < 0 || now > MAX_CLOCK
                    || (!directory.isDirectory() && !directory.mkdirs())) {
                throw new IOException("Unavailable local fault storage");
            }
            try (RandomAccessFile lockFile = new RandomAccessFile(
                    new File(directory, "outbox.lock"), "rw");
                 FileLock lock = lockFile.getChannel().lock()) {
                if (!lock.isValid()) {
                    throw new IOException("Unavailable local fault lock");
                }
                final State state = read();
                state.clock = Math.max(state.clock, now);
                state.entries.removeIf(entry -> state.clock - entry.created
                        >= (entry.pending ? 7 * DAY : DAY));
                final T result = operation.run(state);
                write(state);
                return result;
            }
        }
    }

    private State read() throws IOException {
        final File file = new File(directory, "outbox.bin");
        final State state = new State();
        if (!file.exists()) {
            return state;
        }
        if (file.length() > 4096) {
            throw new IOException("Invalid local fault storage");
        }
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            final int magic = input.readInt();
            final boolean legacy = magic == MAGIC_V1;
            if (!legacy && magic != MAGIC_V2) {
                throw new IOException("Invalid local fault schema");
            }
            state.clock = input.readLong();
            state.sequence = legacy ? 0L : input.readLong();
            final int count = input.readInt();
            if (state.clock < 0 || state.clock > MAX_CLOCK || state.sequence < 0
                    || count < 0 || count > LIMIT) {
                throw new IOException("Invalid local fault bounds");
            }
            for (int i = 0; i < count; i++) {
                final SanitizedAppFault sanitized = readFault(input);
                final long created = input.readLong();
                final int pending = input.readUnsignedByte();
                if (created < 0 || created > state.clock || pending > 1
                        || state.entries.stream().anyMatch(e -> e.fault.equals(sanitized))) {
                    throw new IOException("Invalid local fault entry");
                }
                final Entry entry = new Entry(sanitized, created, pending == 1);
                if (!legacy) {
                    readDelivery(input, entry, state);
                }
                state.entries.add(entry);
            }
            if (input.read() != -1) {
                throw new IOException("Unexpected local fault data");
            }
        }
        return state;
    }

    private static SanitizedAppFault readFault(final DataInputStream input) throws IOException {
        final int fault = input.readUnsignedByte();
        final int component = input.readUnsignedByte();
        if (fault >= SanitizedAppFault.Fault.values().length
                || component >= SanitizedAppFault.Component.values().length) {
            throw new IOException("Unknown local fault category");
        }
        try {
            return new SanitizedAppFault(SanitizedAppFault.Fault.values()[fault],
                    SanitizedAppFault.Component.values()[component],
                    input.readInt(), input.readInt());
        } catch (final IllegalArgumentException e) {
            throw new IOException("Invalid local fault metadata");
        }
    }

    private static void readDelivery(final DataInputStream input, final Entry entry,
                                     final State state) throws IOException {
        final int retained = input.readUnsignedByte();
        final int attempts = input.readUnsignedByte();
        final int leased = input.readUnsignedByte();
        final long generation = input.readLong();
        final long notBefore = input.readLong();
        if (retained > 1 || attempts > MAX_ATTEMPTS || leased > 1
                || generation < 0 || generation > state.sequence
                || (attempts == 0) != (generation == 0)
                || (retained == 1 && !entry.pending)
                || (leased == 1 && (attempts == 0 || !entry.pending || retained == 1))
                || notBefore < entry.created || notBefore > state.clock + DAY) {
            throw new IOException("Invalid local fault delivery state");
        }
        entry.retained = retained == 1;
        entry.attempts = attempts;
        entry.leased = leased == 1;
        entry.generation = generation;
        entry.notBefore = notBefore;
    }

    private void write(final State state) throws IOException {
        final File temporary = new File(directory, "outbox.tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(stream)) {
            output.writeInt(MAGIC_V2);
            output.writeLong(state.clock);
            output.writeLong(state.sequence);
            output.writeInt(state.entries.size());
            for (final Entry entry : state.entries) {
                output.writeByte(entry.fault.fault().ordinal());
                output.writeByte(entry.fault.component().ordinal());
                output.writeInt(entry.fault.version());
                output.writeInt(entry.fault.api());
                output.writeLong(entry.created);
                output.writeBoolean(entry.pending);
                output.writeBoolean(entry.retained);
                output.writeByte(entry.attempts);
                output.writeBoolean(entry.leased);
                output.writeLong(entry.generation);
                output.writeLong(entry.notBefore);
            }
            output.flush();
            stream.getFD().sync();
        }
        if (!temporary.renameTo(new File(directory, "outbox.bin"))) {
            throw new IOException("Could not replace local fault storage");
        }
    }

    private interface Operation<T> {
        T run(State state);
    }

    private static final class State {
        private long clock;
        private long sequence;
        private final List<Entry> entries = new ArrayList<>();
    }

    private static final class Entry {
        private final SanitizedAppFault fault;
        private final long created;
        private boolean pending;
        private boolean retained;
        private boolean leased;
        private int attempts;
        private long generation;
        private long notBefore;

        Entry(final SanitizedAppFault fault, final long created, final boolean pending) {
            this.fault = fault;
            this.created = created;
            this.pending = pending;
            this.notBefore = created;
        }
    }
}
