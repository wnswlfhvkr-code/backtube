package org.schabi.newpipe.local.playlist;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import android.text.TextUtils;
import android.text.format.DateUtils;
import org.schabi.newpipe.views.NewPipeTextView;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.databinding.LocalPlaylistHeaderBinding;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.util.Localization;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class LocalPlaylistDurationTest {
    @Test public void unknownDurationNeverSubtractsSecondsOrClaimsCompleteTotal() throws Exception {
        assertHeader(List.of(635L, -1L), "2 videos • ⏱ 10:35+ (some durations unknown)");
    }

    @Test public void completeKnownDurationsStillProduceCompleteTotal() throws Exception {
        assertHeader(List.of(635L, 287L), "2 videos • ⏱ 15:22");
    }

    @Test public void zeroDurationAlsoLeavesTotalIncomplete() throws Exception {
        assertHeader(List.of(635L, 0L), "2 videos • ⏱ 10:35+ (some durations unknown)");
    }

    private static void assertHeader(final List<Long> seconds, final String expected)
            throws Exception {
        try (MockedStatic<Localization> localization = mockStatic(Localization.class,
                CALLS_REAL_METHODS);
             MockedStatic<TextUtils> texts = mockStatic(TextUtils.class);
             MockedStatic<DateUtils> dates = mockStatic(DateUtils.class)) {
            texts.when(() -> TextUtils.isEmpty(any())).thenAnswer(call ->
                    call.getArgument(0) == null || call.getArgument(0).toString().isEmpty());
            dates.when(() -> DateUtils.formatElapsedTime(anyLong())).thenAnswer(call -> {
                final long value = call.getArgument(0);
                return String.format(java.util.Locale.ROOT, "%02d:%02d", value / 60, value % 60);
            });
            final AppCompatActivity activity = mock(AppCompatActivity.class);
            when(activity.getString(anyInt(), anyString())).thenAnswer(call ->
                    call.getArgument(1) + " (some durations unknown)");
            localization.when(() -> Localization.localizeStreamCount(any(), anyLong()))
                    .thenReturn("2 videos");
            final NewPipeTextView count = mock(NewPipeTextView.class);
            final LocalPlaylistHeaderBinding binding = mock(LocalPlaylistHeaderBinding.class);
            set(binding, "playlistStreamCount", count);
            final LocalPlaylistFragment fragment = new LocalPlaylistFragment();
            set(fragment, "activity", activity);
            set(fragment, "headerBinding", binding);
            final ArrayList<LocalItem> entries = new ArrayList<>();
            for (final long duration : seconds) {
                final StreamInfoItem item = new StreamInfoItem(0, "https://example.invalid/track",
                        "Generated", StreamType.AUDIO_STREAM);
                item.setDuration(duration);
                item.setUploaderName("Generated uploader");
                entries.add(new PlaylistStreamEntry(new StreamEntity(item), 0, 1, 0));
            }
            final Method method = LocalPlaylistFragment.class.getDeclaredMethod(
                    "setStreamCountAndOverallDuration", ArrayList.class);
            method.setAccessible(true);
            method.invoke(fragment, entries);
            final org.mockito.ArgumentCaptor<CharSequence> text =
                    org.mockito.ArgumentCaptor.forClass(CharSequence.class);
            verify(count).setText(text.capture());
            assertEquals(expected, text.getValue().toString());
        }
    }

    private static void set(final Object target, final String name, final Object value)
            throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (final NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
