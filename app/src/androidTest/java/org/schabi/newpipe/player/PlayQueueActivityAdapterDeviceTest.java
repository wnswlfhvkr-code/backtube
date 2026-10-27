package org.schabi.newpipe.player;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ContextThemeWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.ActivityPlayerQueueControlBinding;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueueAdapter;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.disposables.Disposable;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Queue callbacks must release the Activity's previous adapter subscription. */
@RunWith(AndroidJUnit4.class)
public class PlayQueueActivityAdapterDeviceTest {
    @Test
    public void replacingAndClearingQueueReleasesPreviousSubscriptions() throws Exception {
        final Context context = new ContextThemeWrapper(InstrumentationRegistry
                .getInstrumentation().getTargetContext(), R.style.LightTheme);
        final AtomicReference<ActivityPlayerQueueControlBinding> bindingRef =
                new AtomicReference<>();
        final AtomicReference<PlayQueueActivity> activityRef = new AtomicReference<>();
        onMain(() -> {
            bindingRef.set(ActivityPlayerQueueControlBinding.inflate(
                    LayoutInflater.from(context)));
            activityRef.set(new PlayQueueActivity());
        });
        final ActivityPlayerQueueControlBinding binding = bindingRef.get();
        final PlayQueueActivity activity = activityRef.get();
        final Field bindingField = PlayQueueActivity.class.getDeclaredField("queueControlBinding");
        bindingField.setAccessible(true);
        bindingField.set(activity, binding);
        final SinglePlayQueue first = queue("A");
        final SinglePlayQueue second = queue("B");
        first.init();
        second.init();
        final AtomicReference<PlayQueueAdapter> old = new AtomicReference<>();
        final AtomicReference<PlayQueueAdapter> replacement = new AtomicReference<>();
        try {
            onMain(() -> {
                activity.onQueueUpdate(first);
                old.set((PlayQueueAdapter) binding.playQueue.getAdapter());
                activity.onQueueUpdate(second);
                replacement.set((PlayQueueAdapter) binding.playQueue.getAdapter());
            });
            assertNotNull(old.get());
            assertNotNull(replacement.get());
            assertNull("old queue subscription survived replacement", reactor(old.get()));
            assertNull("old selection listener survived replacement", listener(old.get()));

            onMain(() -> {
                activity.onQueueUpdate(null);
                activity.onQueueUpdate(null);
            });
            assertNull("replacement subscription survived clearing", reactor(replacement.get()));
            assertNull(listener(replacement.get()));
        } finally {
            onMain(() -> {
                if (old.get() != null) {
                    old.get().dispose();
                }
                if (replacement.get() != null) {
                    replacement.get().dispose();
                }
                first.dispose();
                second.dispose();
            });
        }
    }

    private static Disposable reactor(final PlayQueueAdapter adapter) throws Exception {
        final Field field = PlayQueueAdapter.class.getDeclaredField("playQueueReactor");
        field.setAccessible(true);
        return (Disposable) field.get(adapter);
    }

    private static Object listener(final PlayQueueAdapter adapter) throws Exception {
        final Field builderField = PlayQueueAdapter.class.getDeclaredField("playQueueItemBuilder");
        builderField.setAccessible(true);
        final Object builder = builderField.get(adapter);
        final Field listenerField = builder.getClass().getDeclaredField("onItemClickListener");
        listenerField.setAccessible(true);
        return listenerField.get(builder);
    }

    private static SinglePlayQueue queue(final String url) {
        return new SinglePlayQueue(new StreamInfoItem(0, url, url, StreamType.VIDEO_STREAM));
    }

    private static void onMain(final Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
