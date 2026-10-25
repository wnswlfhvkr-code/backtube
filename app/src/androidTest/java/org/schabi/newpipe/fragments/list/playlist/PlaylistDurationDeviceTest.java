package org.schabi.newpipe.fragments.list.playlist;

import static org.junit.Assert.assertEquals;

import android.content.Intent;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.databinding.PlaylistHeaderBinding;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.fragments.list.BaseListFragment;
import org.schabi.newpipe.info_list.InfoListAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.FutureTask;

/** Reprocessing a page or refreshing loaded rows must not accumulate stale duration. */
public class PlaylistDurationDeviceTest {
    @Test
    public void durationIsRecomputedFromTheLoadedRowsAcrossRepeatAndRefresh() throws Exception {
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final Intent intent = new Intent(instrumentation.getTargetContext(),
                Batch10TestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Batch10TestActivity activity = (Batch10TestActivity)
                instrumentation.startActivitySync(intent);
        try {
            final FutureTask<Void> task = new FutureTask<>(() -> {
                final PlaylistFragment fragment = new PlaylistFragment();
                final InfoListAdapter adapter = new InfoListAdapter(activity);
                final StreamInfoItem first = item("first", 30);
                final StreamInfoItem second = item("second", 70);
                adapter.addInfoItemList(List.of(first, second));
                set(BaseFragment.class, fragment, "activity", activity);
                set(BaseListFragment.class, fragment, "infoListAdapter", adapter);
                set(PlaylistFragment.class, fragment, "headerBinding",
                        PlaylistHeaderBinding.inflate(LayoutInflater.from(activity),
                                new FrameLayout(activity), false));
                set(PlaylistFragment.class, fragment, "streamCount", 2L);

                updateDuration(fragment, List.of(second), false);
                assertEquals(100L, duration(fragment));

                updateDuration(fragment, List.of(second), false);
                assertEquals(100L, duration(fragment));

                adapter.clearStreamItemList();
                final StreamInfoItem replacement = item("replacement", 20);
                adapter.addInfoItemList(List.of(replacement));
                updateDuration(fragment, List.of(replacement), true);
                assertEquals(20L, duration(fragment));
                return null;
            });
            instrumentation.runOnMainSync(task);
            task.get();
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private static StreamInfoItem item(final String id, final long duration) {
        final StreamInfoItem info = new StreamInfoItem(1, "/watch/" + id, id,
                StreamType.VIDEO_STREAM);
        info.setDuration(duration);
        return info;
    }

    private static void updateDuration(final PlaylistFragment fragment,
                                       final List<StreamInfoItem> page,
                                       final boolean complete) {
        try {
            for (final Method method : PlaylistFragment.class.getDeclaredMethods()) {
                if (method.getName().equals("setStreamCountAndOverallDuration")) {
                    method.setAccessible(true);
                    if (method.getParameterCount() == 2) {
                        method.invoke(fragment, page, complete);
                    } else {
                        method.invoke(fragment, complete);
                    }
                    return;
                }
            }
            throw new AssertionError("duration method missing");
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long duration(final PlaylistFragment fragment) {
        return (long) get(PlaylistFragment.class, fragment, "playlistOverallDurationSeconds");
    }

    private static void set(final Class<?> owner, final Object target, final String name,
                            final Object value) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object get(final Class<?> owner, final Object target, final String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
