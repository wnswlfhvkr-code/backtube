package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.player.playqueue.OfflinePlayQueue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class OfflineShelfOrderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File directory;
    private OfflineStore store;
    private OfflineStore.Entry first;
    private OfflineStore.Entry second;
    private OfflineStore.Entry third;
    private OfflineShelfOrder shelf;

    @Before public void setUp() throws Exception {
        directory = temporary.newFolder();
        store = new OfflineStore(directory, 1000);
        first = save("First");
        second = save("Second");
        third = save("Third");
        shelf = new OfflineShelfOrder(store.list());
    }

    @Test public void dragPreviewDoesNotWriteOrChangeAnActiveSnapshot() throws Exception {
        final OfflinePlayQueue active = new OfflinePlayQueue(store, "Saved");
        assertTrue(shelf.beginDrag(first.id));
        assertTrue(shelf.moveTo(2));
        assertEquals(List.of(second.id, third.id, first.id), ids(shelf.items()));
        assertEquals(List.of(first.id, second.id, third.id), ids(store.list()));
        assertEquals(first.localUrl(), active.getItem(0).getUrl());
        assertEquals(third.localUrl(), active.getItem(2).getUrl());
        final List<OfflineStore.Entry> committed = shelf.finishDrag().commit(store);
        shelf.completeMove(committed);
        assertEquals(ids(committed), ids(new OfflineStore(directory, 1000).list()));
        assertEquals(first.localUrl(), active.getItem(0).getUrl());
        assertEquals(second.localUrl(), new OfflinePlayQueue(store, "Saved").getItem(0).getUrl());
    }

    @Test public void failedDropRestoresPreviewAndRetainsTheDiskOrder() throws Exception {
        assertTrue(shelf.beginDrag(third.id));
        assertTrue(shelf.moveTo(0));
        final OfflineShelfOrder.Move move = shelf.finishDrag();
        assertTrue(new File(directory, "shelf-order.new").mkdir());
        assertThrows(IOException.class, () -> move.commit(store));
        shelf.cancelDrag();
        assertEquals(List.of(first.id, second.id, third.id), ids(shelf.items()));
        assertEquals(ids(shelf.items()), ids(store.list()));
        assertEquals(10, store.file(first).length());
        assertTrue(new File(directory, "shelf-order.new").delete());
        assertEquals(ids(shelf.items()), ids(new OfflineStore(directory, 1000).list()));
    }

    @Test public void cancelledDragRestoresWithoutSaving() throws Exception {
        assertTrue(shelf.beginDrag(first.id));
        assertTrue(shelf.moveTo(1));
        shelf.cancelDrag();
        assertNull(shelf.finishDrag());
        assertEquals(ids(store.list()), ids(shelf.items()));
    }

    @Test public void repeatedMovesTrackTheDraggedIdentity() throws Exception {
        assertTrue(shelf.beginDrag(first.id));
        assertTrue(shelf.moveTo(1));
        assertTrue(shelf.moveTo(2));
        assertTrue(shelf.moveTo(0));
        assertNull(shelf.finishDrag());
        assertEquals(ids(store.list()), ids(shelf.items()));
    }

    @Test public void newSavesDuringADragRemainAfterTheCommittedMove() throws Exception {
        assertTrue(shelf.beginDrag(third.id));
        assertTrue(shelf.moveTo(0));
        final OfflineStore.Entry added = save("Added during drag");
        assertFalse(shelf.replace(store.list()));
        shelf.completeMove(shelf.finishDrag().commit(store));
        assertEquals(List.of(third.id, first.id, second.id, added.id), ids(shelf.items()));
    }

    @Test public void deletedDropTargetFailsWithoutMovingAnotherSavedFile() throws Exception {
        assertTrue(shelf.beginDrag(third.id));
        assertTrue(shelf.moveTo(0));
        final OfflineShelfOrder.Move move = shelf.finishDrag();
        store.delete(first.id);
        assertThrows(IOException.class, () -> move.commit(store));
        shelf.cancelDrag();
        shelf.replace(store.list());
        assertEquals(List.of(second.id, third.id), ids(shelf.items()));
    }

    @Test public void accessibleMovesUseTheSameDurableIdentityBasedCommit() throws Exception {
        assertNull(shelf.moveBy(first.id, -1));
        assertNull(shelf.moveBy(third.id, 1));
        shelf.completeMove(shelf.moveBy(second.id, -1).commit(store));
        assertEquals(List.of(second.id, first.id, third.id), ids(shelf.items()));
        assertEquals(ids(shelf.items()), ids(new OfflineStore(directory, 1000).list()));
    }

    @Test public void invalidOrSecondDragCannotChangeSelection() {
        assertFalse(shelf.beginDrag("missing"));
        assertTrue(shelf.beginDrag(second.id));
        assertFalse(shelf.beginDrag(first.id));
        assertFalse(shelf.moveTo(-1));
        assertFalse(shelf.moveTo(3));
        assertEquals(List.of(first.id, second.id, third.id), ids(shelf.items()));
    }

    @Test public void staleRefreshCannotReplacePreviewWhileDropIsBeingSaved() throws Exception {
        assertTrue(shelf.beginDrag(first.id));
        assertTrue(shelf.moveTo(2));
        final OfflineShelfOrder.Move move = shelf.finishDrag();
        assertFalse(shelf.replace(store.list()));
        assertFalse(shelf.beginDrag(second.id));
        assertNull(shelf.moveBy(second.id, 1));
        assertEquals(List.of(second.id, third.id, first.id), ids(shelf.items()));
        // A failed write/cancel releases the guard and restores the last committed order.
        shelf.cancelDrag();
        assertTrue(shelf.replace(store.list()));
        assertEquals(List.of(first.id, second.id, third.id), ids(shelf.items()));
        assertEquals(first.id, move.id);
    }

    @Test public void emptyShelfHasNoDragOrMove() {
        final OfflineShelfOrder empty = new OfflineShelfOrder(List.of());
        assertFalse(empty.beginDrag(first.id));
        assertFalse(empty.moveTo(0));
        assertNull(empty.moveBy(first.id, 1));
        assertNull(empty.finishDrag());
    }

    private OfflineStore.Entry save(final String title) throws IOException {
        final OfflineStore.Entry entry = store.create(title, "", 0,
                "content://fixture/local", "audio/wav");
        store.copy(entry.id, new ByteArrayInputStream(new byte[10]));
        return store.get(entry.id);
    }

    private static List<String> ids(final List<OfflineStore.Entry> entries) {
        return entries.stream().map(entry -> entry.id).collect(Collectors.toList());
    }
}
