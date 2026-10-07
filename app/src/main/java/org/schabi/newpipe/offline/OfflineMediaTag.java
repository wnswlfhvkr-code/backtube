package org.schabi.newpipe.offline;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Local metadata deliberately has no remote StreamInfo or recommendation lookup. */
public final class OfflineMediaTag implements MediaItemTag {
    private final PlayQueueItem item;
    @Nullable private final Object extras;

    public OfflineMediaTag(final PlayQueueItem item) {
        this(item, null);
    }

    private OfflineMediaTag(final PlayQueueItem item, @Nullable final Object extras) {
        this.item = item;
        this.extras = extras;
    }

    @NonNull
    @Override public Optional<StreamInfo> getMaybeStreamInfo() {
        final StreamInfo info = new StreamInfo(item.getServiceId(), item.getUrl(), item.getUrl(),
                item.getStreamType(), item.getUrl(), item.getTitle(), 0);
        info.setDuration(item.getDuration());
        info.setUploaderName(item.getUploader());
        return Optional.of(info);
    }

    @Override public List<Exception> getErrors() {
        return Collections.emptyList();
    }

    @Override public int getServiceId() {
        return item.getServiceId();
    }

    @Override public String getTitle() {
        return item.getTitle();
    }

    @Override public String getUploaderName() {
        return item.getUploader();
    }

    @Override public long getDurationSeconds() {
        return item.getDuration();
    }

    @Override public String getStreamUrl() {
        return item.getUrl();
    }

    @Override public String getThumbnailUrl() {
        return "";
    }

    @Override public String getUploaderUrl() {
        return "";
    }

    @Override public StreamType getStreamType() {
        return item.getStreamType();
    }

    @Override public <T> Optional<T> getMaybeExtras(@NonNull final Class<T> type) {
        return type.isInstance(extras) ? Optional.of(type.cast(extras)) : Optional.empty();
    }

    @Override public <T> MediaItemTag withExtras(@NonNull final T extra) {
        return new OfflineMediaTag(item, extra);
    }
}
