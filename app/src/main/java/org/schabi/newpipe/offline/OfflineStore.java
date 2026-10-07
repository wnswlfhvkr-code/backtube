package org.schabi.newpipe.offline;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Durable, local-only copies. The source file is never modified or deleted. */
public final class OfflineStore {
    public static final long DEFAULT_LIMIT = 500_000_000L;
    public static final long DEFAULT_LIFETIME = 7L * 24 * 60 * 60 * 1000;
    public static final String LOCAL_PREFIX = "backtube-offline:";

    public enum State { IMPORTING, DOWNLOADING, READY, INTERRUPTED, FAILED, EXPIRED }

    public static final class Entry {
        public final String id;
        public final String title;
        public final String origin;
        public final int serviceId;
        public final String source;
        public final String mime;
        public final State state;
        public final long bytes;
        public final long expiresAt;

        @SuppressWarnings("ParameterNumber")
        private Entry(final String id, final String title, final String origin,
                      final int serviceId, final String source, final String mime,
                      final State state, final long bytes, final long expiresAt) {
            this.id = id;
            this.title = title;
            this.origin = origin;
            this.serviceId = serviceId;
            this.source = source;
            this.mime = mime;
            this.state = state;
            this.bytes = bytes;
            this.expiresAt = expiresAt;
        }

        private Entry changed(final State next, final long length, final long expiry) {
            return new Entry(id, title, origin, serviceId, source, mime, next, length, expiry);
        }

        public String localUrl() {
            return LOCAL_PREFIX + id;
        }
    }

    private final File directory;
    private final long limit;
    private final long lifetime;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Integer> readers = new LinkedHashMap<>();

    public OfflineStore(final File directory, final long limit, final long lifetime,
                        final LongSupplier clock) throws IOException {
        this.directory = directory;
        this.limit = limit;
        this.lifetime = lifetime;
        this.clock = clock;
        if (limit <= 0 || lifetime <= 0 || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Offline storage unavailable");
        }
        final File[] files = directory.listFiles();
        if (files == null) {
            throw new IOException("Cannot read offline storage");
        }
        for (final File metadata : files) {
            if (!metadata.getName().endsWith(".properties")) {
                continue;
            }
            final Properties properties = new Properties();
            try (InputStream input = new FileInputStream(metadata)) {
                properties.load(input);
                final String id = properties.getProperty("id");
                UUID.fromString(id);
                if (!metadata.getName().equals(id + ".properties")) {
                    throw new IllegalArgumentException("Invalid offline identity");
                }
                final Entry entry = new Entry(id, properties.getProperty("title", ""),
                        properties.getProperty("origin", ""),
                        Integer.parseInt(properties.getProperty("service", "0")),
                        properties.getProperty("source", ""),
                        properties.getProperty("mime", "application/octet-stream"),
                        State.valueOf(properties.getProperty("state")),
                        Long.parseLong(properties.getProperty("bytes", "0")),
                        Long.parseLong(properties.getProperty("expires", "0")));
                entries.put(id, entry);
            } catch (final IllegalArgumentException error) {
                throw new IOException("Invalid offline metadata", error);
            }
        }
        for (final Entry entry : new ArrayList<>(entries.values())) {
            if (entry.state == State.IMPORTING) {
                removeFile(part(entry.id));
                removeFile(file(entry));
                save(entry.changed(State.INTERRUPTED, 0, 0));
            }
        }
        // Crash between rename and metadata commit, or deletion and unlink, leaves only orphans.
        for (final File candidate : files) {
            final String name = candidate.getName();
            if (name.endsWith(".new") || name.endsWith(".part")
                    || (name.endsWith(".media")
                    && !entries.containsKey(name.substring(0, name.length() - 6)))) {
                removeFile(candidate);
            }
        }
        refresh();
    }

    public synchronized Entry beginDownload(final String title, final String origin,
                                            final int serviceId, final String mime)
            throws IOException {
        final Entry entry = new Entry(UUID.randomUUID().toString(), title, origin, serviceId,
                "giga", mime, State.DOWNLOADING, 0, 0);
        save(entry);
        if (!file(entry).createNewFile()) {
            throw new IOException("Cannot create offline download");
        }
        return entry;
    }

    public synchronized void checkGrowth(final String id, final long targetLength)
            throws IOException {
        final Entry entry = require(id);
        if (entry.state != State.DOWNLOADING) {
            throw new IOException("Offline download removed or complete");
        }
        final long growth = Math.max(0, targetLength - file(entry).length());
        if (growth > limit - usedBytes()
                || directory.getUsableSpace() < growth + 1048576L) {
            throw new IOException("Not enough offline storage space");
        }
    }

    public synchronized void finishDownload(final String id) throws IOException {
        final Entry entry = require(id);
        final long size = file(entry).length();
        if (entry.state == State.READY) {
            return;
        }
        if (entry.state != State.DOWNLOADING || size <= 0 || usedBytes() > limit) {
            throw new IOException("Incomplete or oversized download");
        }
        save(entry.changed(State.READY, size, clock.getAsLong() + lifetime));
    }

    public synchronized Entry create(final String title, final String origin, final int serviceId,
                                     final String source, final String mime) throws IOException {
        final Entry entry = new Entry(UUID.randomUUID().toString(), title, origin, serviceId,
                source, mime, State.IMPORTING, 0, 0);
        save(entry);
        return entry;
    }

    /**
     * Runs off the UI thread. A retry restarts the local copy, never a network request.
     * @param id managed entry identity
     * @param input source bytes; closed on completion or failure
     */
    public void copy(final String id, final InputStream input) throws IOException {
        synchronized (this) {
            final Entry entry = require(id);
            if (entry.state != State.IMPORTING) {
                throw new IOException("Copy cancelled or not prepared");
            }
            removeFile(part(id));
            save(entry.changed(State.IMPORTING, 0, 0));
        }
        try (InputStream source = input; FileOutputStream output = new FileOutputStream(part(id))) {
            final byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = source.read(buffer)) != -1) {
                synchronized (this) {
                    final Entry entry = require(id);
                    if (entry.state != State.IMPORTING || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Copy cancelled");
                    }
                    if (count > limit - usedBytes()
                            || directory.getUsableSpace() < count + 1048576L) {
                        throw new IOException("Not enough offline storage space");
                    }
                    output.write(buffer, 0, count);
                    entries.put(id, entry.changed(State.IMPORTING, entry.bytes + count, 0));
                }
            }
            output.getFD().sync();
            synchronized (this) {
                final Entry entry = require(id);
                if (entry.state != State.IMPORTING || entry.bytes == 0) {
                    throw new IOException("Copy incomplete or empty");
                }
                if (!part(id).renameTo(file(entry))) {
                    throw new IOException("Cannot finish offline copy");
                }
                save(entry.changed(State.READY, entry.bytes, clock.getAsLong() + lifetime));
            }
        } catch (final IOException error) {
            synchronized (this) {
                removeFile(part(id));
                final Entry entry = entries.get(id);
                if (entry != null) {
                    removeFile(file(entry));
                    save(entry.changed(entry.state == State.INTERRUPTED
                            ? State.INTERRUPTED : State.FAILED, 0, 0));
                }
            }
            throw error;
        }
    }

    public synchronized void prepareRetry(final String id) throws IOException {
        final Entry entry = require(id);
        removeFile(part(id));
        save(entry.changed(State.IMPORTING, 0, 0));
    }

    public synchronized void interrupt(final String id) throws IOException {
        final Entry entry = require(id);
        if (entry.state == State.IMPORTING) {
            save(entry.changed(State.INTERRUPTED, entry.bytes, 0));
        }
    }

    public synchronized void fail(final String id) throws IOException {
        final Entry entry = entries.get(id);
        if (entry != null && entry.state != State.READY
                && entry.state != State.INTERRUPTED) {
            removeFile(part(id));
            save(entry.changed(State.FAILED, 0, 0));
        }
    }

    public synchronized List<Entry> list() throws IOException {
        refresh();
        return new ArrayList<>(entries.values());
    }

    public synchronized Entry get(final String id) throws IOException {
        refresh();
        return entries.get(id);
    }

    public synchronized Entry find(final int serviceId, final String url) throws IOException {
        refresh();
        for (final Entry entry : entries.values()) {
            if (entry.state == State.READY && (entry.localUrl().equals(url)
                    || (entry.serviceId == serviceId && !entry.origin.isEmpty()
                    && entry.origin.equals(url)))) {
                return entry;
            }
        }
        return null;
    }

    public synchronized long usedBytes() {
        long total = 0;
        final File[] files = directory.listFiles();
        if (files != null) {
            for (final File file : files) {
                if (file.getName().endsWith(".media") || file.getName().endsWith(".part")) {
                    total += file.length();
                }
            }
        }
        return total;
    }

    public File file(final Entry entry) {
        return new File(directory, entry.id + ".media");
    }

    public synchronized File retain(final String id) throws IOException {
        final Entry entry = get(id);
        if (entry == null || entry.state != State.READY) {
            throw new IOException("Offline copy missing or expired");
        }
        readers.put(id, readers.getOrDefault(id, 0) + 1);
        return file(entry);
    }

    public synchronized void release(final String id) throws IOException {
        final int remaining = readers.getOrDefault(id, 1) - 1;
        if (remaining > 0) {
            readers.put(id, remaining);
        } else {
            readers.remove(id);
            final Entry entry = entries.get(id);
            if (entry == null || entry.state != State.READY) {
                removeFile(new File(directory, id + ".media"));
            }
        }
    }

    public synchronized void delete(final String id) throws IOException {
        final Entry entry = entries.get(id);
        if (entry == null) {
            return;
        }
        removeFile(new File(directory, id + ".properties"));
        entries.remove(id);
        removeFile(part(id));
        if (!readers.containsKey(id)) {
            removeFile(file(entry));
        }
    }

    private void refresh() throws IOException {
        for (final Entry entry : new ArrayList<>(entries.values())) {
            if (entry.state == State.EXPIRED && !readers.containsKey(entry.id)) {
                removeFile(file(entry));
            }
            if (entry.state == State.DOWNLOADING) {
                entries.put(entry.id, entry.changed(State.DOWNLOADING, file(entry).length(), 0));
                continue;
            }
            if (entry.state != State.READY) {
                continue;
            }
            if (clock.getAsLong() >= entry.expiresAt) {
                save(entry.changed(State.EXPIRED, entry.bytes, entry.expiresAt));
                if (!readers.containsKey(entry.id)) {
                    removeFile(file(entry));
                }
            } else if (!file(entry).isFile() || file(entry).length() != entry.bytes) {
                save(entry.changed(State.FAILED, entry.bytes, entry.expiresAt));
            }
        }
    }

    private Entry require(final String id) throws IOException {
        final Entry entry = entries.get(id);
        if (entry == null) {
            throw new IOException("Offline item removed");
        }
        return entry;
    }

    private File part(final String id) {
        return new File(directory, id + ".part");
    }

    private void save(final Entry entry) throws IOException {
        final Properties properties = new Properties();
        properties.setProperty("id", entry.id);
        properties.setProperty("title", entry.title);
        properties.setProperty("origin", entry.origin);
        properties.setProperty("service", Integer.toString(entry.serviceId));
        properties.setProperty("source", entry.source);
        properties.setProperty("mime", entry.mime);
        properties.setProperty("state", entry.state.name());
        properties.setProperty("bytes", Long.toString(entry.bytes));
        properties.setProperty("expires", Long.toString(entry.expiresAt));
        final File pending = new File(directory, entry.id + ".new");
        try (FileOutputStream output = new FileOutputStream(pending)) {
            properties.store(output, "backtube temporary media");
            output.getFD().sync();
        }
        if (!pending.renameTo(new File(directory, entry.id + ".properties"))) {
            throw new IOException("Cannot persist offline state");
        }
        entries.put(entry.id, entry);
    }

    private static void removeFile(final File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Cannot remove offline file");
        }
    }
}
