package org.schabi.newpipe.player.helper;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import java.util.HashSet;
import java.util.Set;

/** Stores videos and channels excluded from automatically queued recommendations. */
public final class RecommendationExclusions {
    private static final String EXCLUDED_VIDEOS_KEY = "personal_recommendation_excluded_videos";
    private static final String EXCLUDED_CHANNELS_KEY = "personal_recommendation_excluded_channels";

    @NonNull
    private final SharedPreferences preferences;

    public RecommendationExclusions(@NonNull final SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public boolean excludes(final int serviceId, @Nullable final String videoUrl,
                            @Nullable final String uploaderUrl) {
        return contains(EXCLUDED_VIDEOS_KEY, itemKey(serviceId, videoUrl))
                || contains(EXCLUDED_CHANNELS_KEY, itemKey(serviceId, uploaderUrl));
    }

    public boolean excludeVideo(@NonNull final PlayQueueItem item) {
        return add(EXCLUDED_VIDEOS_KEY, itemKey(item.getServiceId(), item.getUrl()));
    }

    public boolean excludeChannel(@NonNull final PlayQueueItem item) {
        return add(EXCLUDED_CHANNELS_KEY, itemKey(item.getServiceId(), item.getUploaderUrl()));
    }

    public void allowVideo(@NonNull final PlayQueueItem item) {
        remove(EXCLUDED_VIDEOS_KEY, itemKey(item.getServiceId(), item.getUrl()));
    }

    public void allowChannel(@NonNull final PlayQueueItem item) {
        remove(EXCLUDED_CHANNELS_KEY, itemKey(item.getServiceId(), item.getUploaderUrl()));
    }

    public void clear() {
        preferences.edit().remove(EXCLUDED_VIDEOS_KEY).remove(EXCLUDED_CHANNELS_KEY).apply();
    }

    public boolean hasExclusions() {
        return hasEntries(EXCLUDED_VIDEOS_KEY) || hasEntries(EXCLUDED_CHANNELS_KEY);
    }

    private boolean add(@NonNull final String preferenceKey, @Nullable final String itemKey) {
        if (itemKey == null) {
            return false;
        }
        final Set<String> items = itemsFor(preferenceKey);
        if (!items.add(itemKey)) {
            return false;
        }
        preferences.edit().putStringSet(preferenceKey, items).apply();
        return true;
    }

    private void remove(@NonNull final String preferenceKey, @Nullable final String itemKey) {
        if (itemKey == null) {
            return;
        }
        final Set<String> items = itemsFor(preferenceKey);
        if (items.remove(itemKey)) {
            preferences.edit().putStringSet(preferenceKey, items).apply();
        }
    }

    private boolean contains(@NonNull final String preferenceKey, @Nullable final String itemKey) {
        return itemKey != null && itemsFor(preferenceKey).contains(itemKey);
    }

    private boolean hasEntries(@NonNull final String preferenceKey) {
        return !itemsFor(preferenceKey).isEmpty();
    }

    @NonNull
    private Set<String> itemsFor(@NonNull final String preferenceKey) {
        try {
            final Set<String> stored = preferences.getStringSet(preferenceKey, null);
            return stored == null ? new HashSet<>() : new HashSet<>(stored);
        } catch (final ClassCastException ignored) {
            return new HashSet<>();
        }
    }

    @Nullable
    private static String itemKey(final int serviceId, @Nullable final String url) {
        return url == null || url.trim().isEmpty() ? null : serviceId + "|" + url;
    }
}
