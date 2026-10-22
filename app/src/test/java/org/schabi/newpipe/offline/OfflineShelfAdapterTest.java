package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;

import androidx.recyclerview.widget.ListUpdateCallback;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.ArrayList;

/** Structural refreshes must preserve row identities instead of invalidating the whole shelf. */
public class OfflineShelfAdapterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private OfflineStore.Entry first;
    private OfflineStore.Entry second;
    private final Updates updates = new Updates();

    @Before public void setUp() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        first = store.create("Same title", "", 0, "content://fixture/first", "audio/wav");
        second = store.create("Same title", "", 0, "content://fixture/second", "audio/wav");
    }

    @Test public void reorderUsesSavedIdentityAndReportsAMove() {
        OfflineShelfAdapter.calculateUpdates(List.of(first, second), List.of(second, first))
                .dispatchUpdatesTo(updates);
        assertEquals(1, updates.moves);
        assertEquals(2, updates.changed);
    }

    @Test public void refreshRebindsLiveStatusEvenWhenEntryObjectsAreUnchanged() {
        OfflineShelfAdapter.calculateUpdates(List.of(first, second), List.of(first, second))
                .dispatchUpdatesTo(updates);
        assertEquals(2, updates.changed);
        assertEquals(0, updates.moves);
    }

    @Test public void emptyAndRepopulatedShelfUseRemovalAndInsertionEvents() {
        OfflineShelfAdapter.calculateUpdates(List.of(first, second), List.of())
                .dispatchUpdatesTo(updates);
        assertEquals(2, updates.removed);
        OfflineShelfAdapter.calculateUpdates(List.of(), List.of(second)).dispatchUpdatesTo(updates);
        assertEquals(1, updates.inserted);
        assertEquals(0, updates.changed);
    }

    @Test public void cancelledPreviewMapsBothSavedIdentitiesBackToTheirCommittedPositions() {
        final OfflineShelfOrder order = new OfflineShelfOrder(List.of(first, second));
        org.junit.Assert.assertTrue(order.beginDrag(first.id));
        org.junit.Assert.assertTrue(order.moveTo(1));
        final List<OfflineStore.Entry> preview = new ArrayList<>(order.items());
        order.cancelDrag();
        final var diff = OfflineShelfAdapter.calculateUpdates(preview, order.items());
        assertEquals(1, diff.convertOldPositionToNew(0));
        assertEquals(0, diff.convertOldPositionToNew(1));
        diff.dispatchUpdatesTo(updates);
        assertEquals(1, updates.moves);
        assertEquals(2, updates.changed);
    }

    @Test public void removingOneDuplicateTitleKeepsTheOtherRowsIdentity() {
        final var diff = OfflineShelfAdapter.calculateUpdates(List.of(first, second),
                List.of(second));
        assertEquals(-1, diff.convertOldPositionToNew(0));
        assertEquals(0, diff.convertOldPositionToNew(1));
        diff.dispatchUpdatesTo(updates);
        assertEquals(1, updates.removed);
        assertEquals(1, updates.changed);
        assertEquals(0, updates.inserted);
    }

    private static final class Updates implements ListUpdateCallback {
        private int changed;
        private int moves;
        private int removed;
        private int inserted;

        @Override public void onChanged(final int start, final int count, final Object payload) {
            changed += count;
        }

        @Override public void onMoved(final int from, final int to) {
            moves++;
        }

        @Override public void onRemoved(final int start, final int count) {
            removed += count;
        }

        @Override public void onInserted(final int start, final int count) {
            inserted += count;
        }
    }
}
