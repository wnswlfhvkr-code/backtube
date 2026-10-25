package org.schabi.newpipe.info_list;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;

/** A footer is shifted by insertion; a second move notification would corrupt its position. */
public class InfoListAdapterInsertionDeviceTest {
    @Test
    public void addingRowsBeforeFooterDoesNotReportAnExtraMove() throws Exception {
        onMain(() -> {
            final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            final InfoListAdapter adapter = new InfoListAdapter(context);
            adapter.addInfoItemList(List.of(item("a"), item("b")));
            adapter.showFooter(true);
            final List<String> events = new ArrayList<>();
            adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override
                public void onItemRangeInserted(final int positionStart, final int itemCount) {
                    events.add("insert:" + positionStart + ":" + itemCount);
                }

                @Override
                public void onItemRangeMoved(final int fromPosition, final int toPosition,
                                             final int itemCount) {
                    events.add("move:" + fromPosition + ":" + toPosition);
                }
            });

            adapter.addInfoItemList(List.of(item("c")));

            assertEquals(List.of("insert:2:1"), events);
            assertEquals(4, adapter.getItemCount());
            assertEquals("c", adapter.getItemsList().get(2).getName());
        });
    }

    @Test
    public void headerAndFooterKeepTheirPositionsAcrossRepeatedPages() throws Exception {
        onMain(() -> {
            final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            final InfoListAdapter adapter = new InfoListAdapter(context);
            adapter.setHeaderSupplier(() -> new android.view.View(context));
            adapter.showFooter(true);
            final List<String> events = new ArrayList<>();
            adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override
                public void onItemRangeInserted(final int positionStart, final int itemCount) {
                    events.add("insert:" + positionStart + ":" + itemCount);
                }

                @Override
                public void onItemRangeMoved(final int fromPosition, final int toPosition,
                                             final int itemCount) {
                    events.add("move:" + fromPosition + ":" + toPosition);
                }
            });

            adapter.addInfoItemList(List.of(item("a"), item("b")));
            adapter.addInfoItemList(List.of(item("c")));

            assertEquals(List.of("insert:1:2", "insert:3:1"), events);
            assertEquals(5, adapter.getItemCount());
            assertEquals("c", adapter.getItemsList().get(2).getName());
        });
    }

    private static StreamInfoItem item(final String name) {
        return new StreamInfoItem(1, "/watch/" + name, name, StreamType.VIDEO_STREAM);
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
