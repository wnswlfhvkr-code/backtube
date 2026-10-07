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

/** Local only. No transport, credentials, raw traces or user identifiers. */
public final class AppFaultOutbox {
    private static final int MAGIC = 0x41465031;
    private static final int LIMIT = 8;
    private static final long DAY = 86400000L;
    private final File directory;

    public enum Result { ADDED, DUPLICATE, RATE_LIMITED, FULL }

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

    /**
     * A future authorized transport may read only these already sanitized payloads.
     * @param now wall clock milliseconds, clamped to the persisted high-water mark
     * @return a snapshot of unexpired pending sanitized records
     */
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

    /**
     * Call only after confirmed delivery; receipt remains for the daily dedupe window.
     * @param fault delivered sanitized record
     * @param now wall clock milliseconds, clamped to the persisted high-water mark
     */
    public void acknowledge(final SanitizedAppFault fault, final long now) throws IOException {
        access(now, state -> {
            for (final Entry entry : state.entries) {
                if (entry.fault.equals(fault)) {
                    entry.pending = false;
                }
            }
            return null;
        });
    }

    // Both same-process threads and ACRA's separate sender process share one transaction lock.
    private <T> T access(final long now, final Operation<T> operation) throws IOException {
        synchronized (AppFaultOutbox.class) {
            if (now < 0 || (!directory.isDirectory() && !directory.mkdirs())) {
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
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid local fault schema");
            }
            state.clock = input.readLong();
            final int count = input.readInt();
            if (state.clock < 0 || count < 0 || count > LIMIT) {
                throw new IOException("Invalid local fault bounds");
            }
            for (int i = 0; i < count; i++) {
                final int fault = input.readUnsignedByte();
                final int component = input.readUnsignedByte();
                if (fault >= SanitizedAppFault.Fault.values().length
                        || component >= SanitizedAppFault.Component.values().length) {
                    throw new IOException("Unknown local fault category");
                }
                final SanitizedAppFault sanitized;
                try {
                    sanitized = new SanitizedAppFault(SanitizedAppFault.Fault.values()[fault],
                            SanitizedAppFault.Component.values()[component],
                            input.readInt(), input.readInt());
                } catch (final IllegalArgumentException e) {
                    throw new IOException("Invalid local fault metadata");
                }
                final long created = input.readLong();
                final int pending = input.readUnsignedByte();
                if (created < 0 || created > state.clock || pending > 1
                        || state.entries.stream().anyMatch(e -> e.fault.equals(sanitized))) {
                    throw new IOException("Invalid local fault entry");
                }
                state.entries.add(new Entry(sanitized, created, pending == 1));
            }
            if (input.read() != -1) {
                throw new IOException("Unexpected local fault data");
            }
        }
        return state;
    }

    private void write(final State state) throws IOException {
        final File temporary = new File(directory, "outbox.tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(stream)) {
            output.writeInt(MAGIC);
            output.writeLong(state.clock);
            output.writeInt(state.entries.size());
            for (final Entry entry : state.entries) {
                output.writeByte(entry.fault.fault().ordinal());
                output.writeByte(entry.fault.component().ordinal());
                output.writeInt(entry.fault.version());
                output.writeInt(entry.fault.api());
                output.writeLong(entry.created);
                output.writeBoolean(entry.pending);
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
        private final List<Entry> entries = new ArrayList<>();
    }

    private static final class Entry {
        private final SanitizedAppFault fault;
        private final long created;
        private boolean pending;

        Entry(final SanitizedAppFault fault, final long created, final boolean pending) {
            this.fault = fault;
            this.created = created;
            this.pending = pending;
        }
    }
}
