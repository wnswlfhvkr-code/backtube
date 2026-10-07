package org.schabi.newpipe.offline;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application-owned local copy jobs. Never opens HTTP or invokes a service extractor. */
public final class OfflineLibrary {
    private static OfflineLibrary instance;
    private final OfflineStore store;
    private final ExecutorService copier = Executors.newSingleThreadExecutor();
    private final Set<String> jobs = ConcurrentHashMap.newKeySet();
    private final Context context;

    private OfflineLibrary(final Context context) throws IOException {
        this.context = context.getApplicationContext();
        store = new OfflineStore(new File(context.getFilesDir(), "offline"),
                OfflineStore.DEFAULT_LIMIT, OfflineStore.DEFAULT_LIFETIME,
                System::currentTimeMillis);
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
            } catch (final IOException | SecurityException error) {
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

    public SinglePlayQueue queue(final OfflineStore.Entry entry) {
        final StreamInfoItem item = new StreamInfoItem(entry.serviceId, entry.localUrl(),
                entry.title, entry.mime.startsWith("video/")
                ? StreamType.VIDEO_STREAM : StreamType.AUDIO_STREAM);
        item.setUploaderName(context.getString(org.schabi.newpipe.R.string.offline_saved_copy));
        return new SinglePlayQueue(item);
    }

    @Nullable
    public MediaSource source(final PlayQueueItem item) throws IOException {
        final OfflineStore.Entry entry = store.find(item.getServiceId(), item.getUrl());
        if (entry == null) {
            return null;
        }
        final OfflineMediaTag tag = new OfflineMediaTag(item);
        return new ProgressiveMediaSource.Factory(() -> new SavedFileSource(store, entry.id))
                .createMediaSource(tag.asMediaItem().buildUpon()
                        .setUri(Uri.fromFile(store.file(entry))).setMimeType(entry.mime).build());
    }

    /** Keep the opened file alive through expiry/delete until ExoPlayer releases its reader. */
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
