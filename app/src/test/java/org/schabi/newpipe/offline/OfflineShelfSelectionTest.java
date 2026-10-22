package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.util.List;

public class OfflineShelfSelectionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private OfflineStore store;
    private OfflineStore.Entry first;
    private OfflineStore.Entry second;
    private final OfflineShelfSelection selection = new OfflineShelfSelection();

    @Before public void setup() throws Exception {
        store = new OfflineStore(temporary.newFolder(), 1000);
        first = save("First");
        second = save("Second");
    }

    @Test public void modeToggleAndCancelClearStableIdsWithoutPlayback() {
        selection.toggle(first);
        assertTrue(selection.snapshot().isEmpty());
        selection.enter();
        selection.toggle(first);
        selection.toggle(second);
        selection.toggle(first);
        assertEquals(List.of(second.id), selection.snapshot());
        selection.clear();
        assertTrue(selection.isActive());
        assertTrue(selection.snapshot().isEmpty());
        selection.toggle(first);
        selection.cancel();
        assertFalse(selection.isActive());
        assertTrue(selection.snapshot().isEmpty());
    }

    @Test public void reorderKeepsIdentityAndRefreshDropsDeletedOrNonReadyEntries()
            throws Exception {
        selection.enter();
        selection.toggle(second);
        selection.toggle(first);
        final List<String> request = selection.snapshot();
        store.move(second.id, -1);
        assertFalse(selection.reconcile(store.list()));
        assertTrue(selection.contains(first.id));
        store.delete(second.id);
        assertTrue(selection.reconcile(store.list()));
        assertEquals(List.of(first.id), selection.snapshot());
        assertTrue(store.file(first).delete());
        assertTrue(selection.reconcile(store.list()));
        assertTrue(selection.snapshot().isEmpty());
        assertEquals(List.of(second.id, first.id), request);
    }

    @Test public void configurationRestoreDeduplicatesAndReconcilesWithoutSelectingPositions()
            throws Exception {
        selection.restore(true, List.of(second.id, second.id, "deleted", first.id));
        assertTrue(selection.isActive());
        selection.reconcile(store.list());
        assertEquals(List.of(second.id, first.id), selection.snapshot());
        final OfflineStore.Entry unfinished = store.create("Pending", "", 0,
                "content://fixture/pending", "audio/wav");
        selection.toggle(unfinished);
        assertFalse(selection.contains(unfinished.id));
        selection.restore(false, List.of(first.id));
        assertTrue(selection.snapshot().isEmpty());
    }

    private OfflineStore.Entry save(final String title) throws Exception {
        final OfflineStore.Entry item = store.create(title, "", 0,
                "content://fixture/" + title, "audio/wav");
        store.copy(item.id, new ByteArrayInputStream(new byte[20]));
        return store.get(item.id);
    }
}
