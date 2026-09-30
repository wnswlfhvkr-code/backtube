package org.schabi.newpipe.player.helper;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonStringWriter;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.util.ArrayList;
import java.util.List;

/** Stores the loaded portion of the last queue without retaining stream playback URLs. */
public final class LastPlaybackSessionStore {
    private static final String PREFERENCE_KEY = "last_playback_session";
    private static final int VERSION = 1;

    private static final String KEY_VERSION = "version";
    private static final String KEY_INDEX = "index";
    private static final String KEY_POSITION = "position";
    private static final String KEY_REPEAT_MODE = "repeat_mode";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_SERVICE_ID = "service_id";
    private static final String KEY_URL = "url";
    private static final String KEY_TITLE = "title";
    private static final String KEY_UPLOADER = "uploader";
    private static final String KEY_UPLOADER_URL = "uploader_url";
    private static final String KEY_DURATION = "duration";
    private static final String KEY_STREAM_TYPE = "stream_type";
    private static final String KEY_AUTO_QUEUED = "auto_queued";

    @NonNull
    private final SharedPreferences preferences;

    public LastPlaybackSessionStore(@NonNull final SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public void save(@NonNull final PlayQueue queue, final long currentPositionMs,
                     final int repeatMode) {
        if (!isValidQueue(queue, currentPositionMs, repeatMode)) {
            clear();
            return;
        }

        final JsonStringWriter writer = JsonWriter.string();
        writer.object()
                .value(KEY_VERSION, VERSION)
                .value(KEY_INDEX, queue.getIndex())
                .value(KEY_POSITION, currentPositionMs)
                .value(KEY_REPEAT_MODE, repeatMode)
                .array(KEY_ITEMS);
        for (final PlayQueueItem item : queue.getStreams()) {
            writer.object()
                    .value(KEY_SERVICE_ID, item.getServiceId())
                    .value(KEY_URL, item.getUrl())
                    .value(KEY_TITLE, safeString(item.getTitle()))
                    .value(KEY_UPLOADER, safeString(item.getUploader()))
                    .value(KEY_DURATION, item.getDuration())
                    .value(KEY_STREAM_TYPE, item.getStreamType().name())
                    .value(KEY_AUTO_QUEUED, item.isAutoQueued());
            if (item.getUploaderUrl() == null) {
                writer.nul(KEY_UPLOADER_URL);
            } else {
                writer.value(KEY_UPLOADER_URL, item.getUploaderUrl());
            }
            writer.end();
        }
        writer.end().end();

        preferences.edit().putString(PREFERENCE_KEY, writer.done()).apply();
    }

    @Nullable
    public Snapshot load() {
        try {
            final String json = preferences.getString(PREFERENCE_KEY, null);
            if (json == null) {
                return null;
            }
            final JsonObject session = JsonParser.object().from(json);
            if (!session.isNumber(KEY_VERSION) || session.getInt(KEY_VERSION, -1) != VERSION) {
                return clearAndReturnNull();
            }

            final JsonArray items = session.getArray(KEY_ITEMS, null);
            final int index = session.getInt(KEY_INDEX, -1);
            final long position = session.getLong(KEY_POSITION, -1);
            final int repeatMode = session.getInt(KEY_REPEAT_MODE, -1);
            if (items == null || items.isEmpty() || !session.isNumber(KEY_INDEX)
                    || !session.isNumber(KEY_POSITION) || !session.isNumber(KEY_REPEAT_MODE)
                    || index < 0 || index >= items.size() || position < 0
                    || !isValidRepeatMode(repeatMode)) {
                return clearAndReturnNull();
            }

            final List<StreamInfoItem> restoredItems = new ArrayList<>(items.size());
            final List<Boolean> autoQueued = new ArrayList<>(items.size());
            for (final Object value : items) {
                if (!(value instanceof JsonObject)) {
                    return clearAndReturnNull();
                }
                final JsonObject item = (JsonObject) value;
                final String url = item.getString(KEY_URL, "");
                final String type = item.getString(KEY_STREAM_TYPE, "");
                final long duration = item.getLong(KEY_DURATION, -1);
                if (!item.isNumber(KEY_SERVICE_ID) || !item.isString(KEY_URL)
                        || !item.isString(KEY_TITLE) || !item.isString(KEY_UPLOADER)
                        || (!item.isNull(KEY_UPLOADER_URL) && !item.isString(KEY_UPLOADER_URL))
                        || !item.isNumber(KEY_DURATION) || !item.isString(KEY_STREAM_TYPE)
                        || url.trim().isEmpty() || type.isEmpty() || duration < -1
                        || !item.isBoolean(KEY_AUTO_QUEUED)) {
                    return clearAndReturnNull();
                }

                final StreamInfoItem restored = new StreamInfoItem(
                        item.getInt(KEY_SERVICE_ID, 0), url,
                        item.getString(KEY_TITLE, ""), StreamType.valueOf(type));
                restored.setUploaderName(item.getString(KEY_UPLOADER, ""));
                restored.setUploaderUrl(item.isNull(KEY_UPLOADER_URL)
                        ? null : item.getString(KEY_UPLOADER_URL, ""));
                restored.setDuration(duration);
                restoredItems.add(restored);
                autoQueued.add(item.getBoolean(KEY_AUTO_QUEUED));
            }

            // ponytail: restores only loaded entries; add pagination metadata if it becomes needed.
            final PlayQueue queue = new SinglePlayQueue(restoredItems, index);
            for (int i = 0; i < autoQueued.size(); i++) {
                queue.getItem(i).setAutoQueued(autoQueued.get(i));
            }
            queue.setRecovery(index, position);
            return new Snapshot(queue, repeatMode);
        } catch (final JsonParserException | RuntimeException e) {
            return clearAndReturnNull();
        }
    }

    public void clear() {
        preferences.edit().remove(PREFERENCE_KEY).apply();
    }

    @Nullable
    private Snapshot clearAndReturnNull() {
        clear();
        return null;
    }

    private static boolean isValidRepeatMode(final int repeatMode) {
        return repeatMode >= 0 && repeatMode <= 2;
    }

    private static boolean isValidQueue(@NonNull final PlayQueue queue,
                                        final long currentPositionMs, final int repeatMode) {
        if (queue.isEmpty() || currentPositionMs < 0 || !isValidRepeatMode(repeatMode)) {
            return false;
        }
        for (final PlayQueueItem item : queue.getStreams()) {
            if (item == null || item.getUrl().trim().isEmpty() || item.getDuration() < -1
                    || item.getStreamType() == null) {
                return false;
            }
        }
        return true;
    }

    @NonNull
    private static String safeString(@Nullable final String value) {
        return value == null ? "" : value;
    }

    public static final class Snapshot {
        @NonNull
        private final PlayQueue queue;
        private final int repeatMode;

        Snapshot(@NonNull final PlayQueue queue, final int repeatMode) {
            this.queue = queue;
            this.repeatMode = repeatMode;
        }

        @NonNull
        public PlayQueue getQueue() {
            return queue;
        }

        public int getRepeatMode() {
            return repeatMode;
        }
    }
}
