package org.schabi.newpipe.player.playqueue;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.concurrent.FutureTask;

/** Recycled queue rows must display the item being bound, including empty and live fields. */
@RunWith(AndroidJUnit4.class)
public class PlayQueueItemRebindDeviceTest {
    @Test
    public void emptyTitleClearsThePreviousItemsTitle() throws Exception {
        onMain(() -> {
            final Context context = themedContext();
            final PlayQueueItemHolder holder = holder(context);
            final PlayQueueItemBuilder builder = new PlayQueueItemBuilder(context);
            builder.buildStreamInfoItem(holder, item("a", "Previous A", 60));
            assertEquals("Previous A", holder.itemVideoTitleView.getText().toString());

            builder.buildStreamInfoItem(holder, item("b", "", 60));
            assertEquals("", holder.itemVideoTitleView.getText().toString());
        });
    }

    @Test
    public void positiveDurationIsShownAfterUnknownDuration() throws Exception {
        onMain(() -> {
            final Context context = themedContext();
            final PlayQueueItemHolder holder = holder(context);
            final PlayQueueItemBuilder builder = new PlayQueueItemBuilder(context);
            builder.buildStreamInfoItem(holder, item("live", "Live", -1));
            assertEquals(View.GONE, holder.itemDurationView.getVisibility());

            builder.buildStreamInfoItem(holder, item("recorded", "Recorded", 60));
            assertEquals(View.VISIBLE, holder.itemDurationView.getVisibility());
            assertEquals("01:00", holder.itemDurationView.getText().toString());
        });
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation()
                .getTargetContext(), R.style.LightTheme);
    }

    private static PlayQueueItemHolder holder(final Context context) {
        final FrameLayout parent = new FrameLayout(context);
        return new PlayQueueItemHolder(LayoutInflater.from(context)
                .inflate(R.layout.play_queue_item, parent, false));
    }

    private static PlayQueueItem item(final String url, final String name,
                                      final long duration) {
        final StreamInfoItem info = new StreamInfoItem(1, url, name, StreamType.VIDEO_STREAM);
        info.setDuration(duration);
        return new PlayQueueItem(info);
    }

    private static void onMain(final Runnable action) throws Exception {
        final FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        task.get();
    }
}
