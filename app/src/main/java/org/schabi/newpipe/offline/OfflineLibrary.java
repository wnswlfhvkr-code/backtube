package org.schabi.newpipe.offline;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.OfflinePlayQueue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application-owned local copy jobs. Never opens HTTP or invokes a service extractor. */
public final class OfflineLibrary {
    private static OfflineLibrary instance;
    private final OfflineStore store;
    private final ExecutorService copier = Executors.newSingleThreadExecutor();
    private final Set<String> jobs = ConcurrentHashMap.newKeySet();
    private final Set<String> capacityFailures = ConcurrentHashMap.newKeySet();
    private final Context context;

    private OfflineLibrary(final Context context) throws IOException {
        this.context = context.getApplicationContext();
        store = new OfflineStore(new File(context.getFilesDir(), "offline"),
                OfflineStore.DEFAULT_LIMIT);
    }

    public static synchronized OfflineLibrary get(final Context context) throws IOException {
        if (instance == null) {
            instance = new OfflineLibrary(context);
        }
        return instance;
    }

    public OfflineStore store() {
        return store;
    }

    public boolean isCopying(final String id) {
        return jobs.contains(id);
    }

    public boolean isCapacityFailure(final String id) {
        return capacityFailures.contains(id);
    }

    public OfflineStore.Entry importUri(final Uri uri, final String title, final String origin,
                                        final int serviceId, final String mime) throws IOException {
        requireLocal(uri);
        final OfflineStore.Entry entry = store.create(title, origin, serviceId,
                uri.toString(), mime);
        retry(entry.id);
        return entry;
    }

    public void retry(final String id) throws IOException {
        final OfflineStore.Entry entry = store.get(id);
        if (entry == null || entry.state == OfflineStore.State.READY || !jobs.add(id)) {
            return;
        }
        capacityFailures.remove(id);
        final Uri uri = Uri.parse(entry.source);
        try {
            requireLocal(uri);
            store.prepareRetry(id);
        } catch (final IOException error) {
            jobs.remove(id);
            throw error;
        }
        copier.execute(() -> {
            try {
                final OfflineStore.Entry current = store.get(id);
                if (current == null || current.state == OfflineStore.State.INTERRUPTED) {
                    return;
                }
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input == null) {
                        throw new IOException("Local file unavailable");
                    }
                    store.copy(id, input);
                }
                OfflineSaveNotifications.showSaved(context, current.title);
            } catch (final IOException | SecurityException error) {
                if (error instanceof OfflineStore.StorageFullException) {
                    capacityFailures.add(id);
                }
                try {
                    store.fail(id);
                } catch (final IOException ignored) {
                    // A durable interrupted record is retained if storage itself is unavailable.
                }
            } finally {
                jobs.remove(id);
            }
        });
    }

    private static void requireLocal(final Uri uri) throws IOException {
        if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) {
            throw new IOException("Choose an existing local media file");
        }
    }

    @WorkerThread
    public OfflinePlayQueue queue(final OfflineStore.Entry entry) {
        final StreamInfoItem item = new StreamInfoItem(entry.serviceId, entry.localUrl(),
                entry.title, entry.mime.startsWith("video/")
                ? StreamType.VIDEO_STREAM : StreamType.AUDIO_STREAM);
        item.setUploaderName(context.getString(org.schabi.newpipe.R.string.offline_saved_copy));
        item.setDuration(durationSeconds(entry.serviceId, entry.localUrl()));
        return new OfflinePlayQueue(List.of(item), 0);
    }

    /**
     * Best-effort local metadata, also usable for playlists saved before duration was supplied.
     * @param serviceId stream service
     * @param url internal saved identity
     * @return duration in seconds, or -1 when metadata is unavailable
     */
    @WorkerThread
    public long durationSeconds(final int serviceId, final String url) {
        if (!OfflineStore.isLocalUrl(url)) {
            return -1;
        }
        try {
            final OfflineStore.Entry entry = requireSavedEntry(serviceId, url);
            return OfflineMediaDuration.readSeconds(store.file(entry));
        } catch (final IOException ignored) {
            return -1;
        }
    }

    @WorkerThread
    public OfflinePlayQueue queueAll() throws IOException {
        return new OfflinePlayQueue(store,
                context.getString(org.schabi.newpipe.R.string.offline_saved_copy));
    }

    /**
     * Resolve an internal identity from owned saved metadata, never its original web URL.
     * @param serviceId caller's service selection; the saved identity remains authoritative
     * @param url saved internal identity
     * @return metadata from the owned copy
     */
    @WorkerThread
    public StreamInfo streamInfo(final int serviceId, final String url) throws IOException {
        final OfflineStore.Entry entry = requireSavedEntry(serviceId, url);
        return new OfflineMediaTag(queue(entry).getItem()).getMaybeStreamInfo().orElseThrow();
    }

    @WorkerThread
    public OfflinePlayQueue queueFor(final int serviceId, final String url) throws IOException {
        return queue(requireSavedEntry(serviceId, url));
    }

    private OfflineStore.Entry requireSavedEntry(final int serviceId, final String url)
            throws IOException {
        if (!OfflineStore.isLocalUrl(url)) {
            throw new IOException("Saved identity required");
        }
        final OfflineStore.Entry entry = store.find(serviceId, url);
        if (entry == null || !store.file(entry).canRead()) {
            throw new IOException("Saved copy unavailable");
        }
        return entry;
    }

    @Nullable
    public MediaSource source(final PlayQueueItem item) throws IOException {
        // An origin match describes the saved entry; it does not request saved playback.
        if (!OfflineStore.isLocalUrl(item.getUrl())) {
            return null;
        }
        final OfflineStore.Entry entry = store.find(item.getServiceId(), item.getUrl());
        if (entry == null) {
            return null;
        }
        final OfflineMediaTag tag = new OfflineMediaTag(item);
        return new ProgressiveMediaSource.Factory(() -> new SavedFileSource(store, entry.id))
                .createMediaSource(tag.asMediaItem().buildUpon()
                        .setUri(Uri.fromFile(store.file(entry))).setMimeType(entry.mime).build());
    }

    /** Keep a deleted file alive until ExoPlayer releases its reader. */
    private static final class SavedFileSource implements DataSource {
        private final OfflineStore store;
        private final String id;
        private final FileDataSource delegate = new FileDataSource();
        private boolean retained;

        SavedFileSource(final OfflineStore store, final String id) {
            this.store = store;
            this.id = id;
        }

        @Override public void addTransferListener(final TransferListener listener) {
            delegate.addTransferListener(listener);
        }

        @Override public long open(final DataSpec dataSpec) throws IOException {
            store.retain(id);
            retained = true;
            try {
                return delegate.open(dataSpec);
            } catch (final IOException error) {
                close();
                throw error;
            }
        }

        @Override public int read(final byte[] buffer, final int offset, final int length)
                throws IOException {
            return delegate.read(buffer, offset, length);
        }

        @Nullable @Override public Uri getUri() {
            return delegate.getUri();
        }

        @Override public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                if (retained) {
                    retained = false;
                    store.release(id);
                }
            }
        }
    }
}
