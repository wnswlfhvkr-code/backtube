package org.schabi.newpipe.local.holder;

import static org.junit.Assert.assertEquals;

import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.fragments.list.playlist.Batch10TestActivity;
import org.schabi.newpipe.local.LocalItemBuilder;

import java.util.concurrent.FutureTask;

/** A reused row must not show the previous stream's progress for an unknown duration. */
public class LocalPlaylistRowReuseTest {
    @Test
    public void unknownDurationHidesPreviousProgress() throws Exception {
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final Intent intent = new Intent(instrumentation.getTargetContext(),
                Batch10TestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Batch10TestActivity activity = (Batch10TestActivity)
                instrumentation.startActivitySync(intent);
        try {
            final FutureTask<Void> task = new FutureTask<>(() -> {
                final LocalPlaylistStreamItemHolder holder = new LocalPlaylistStreamItemHolder(
                        new LocalItemBuilder(activity), new FrameLayout(activity));
                holder.updateFromItem(entry(1, 120, 30000), null, null);
                final View progress = holder.itemView.findViewById(R.id.itemProgressView);
                // The test checks row reuse regardless of the user's progress-display preference.
                progress.setVisibility(View.VISIBLE);

                holder.updateFromItem(entry(2, 0, 0), null, null);
                assertEquals(View.GONE, holder.itemDurationView.getVisibility());
                assertEquals(View.GONE, progress.getVisibility());
                return null;
            });
            instrumentation.runOnMainSync(task);
            task.get();
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private static PlaylistStreamEntry entry(final long id, final long duration,
                                             final long progressMillis) {
        final StreamEntity stream = new StreamEntity(id, 0,
                "https://example.test/" + id, "Test " + id, StreamType.VIDEO_STREAM,
                duration, "Uploader", null, null, null, null, null, null);
        return new PlaylistStreamEntry(stream, progressMillis, id, (int) id);
    }
}
