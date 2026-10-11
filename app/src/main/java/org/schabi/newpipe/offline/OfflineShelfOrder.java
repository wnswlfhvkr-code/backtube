package org.schabi.newpipe.offline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Main-thread drag preview; only an immutable Move performs background disk work. */
final class OfflineShelfOrder {
    private List<OfflineStore.Entry> committed;
    private List<OfflineStore.Entry> visible;
    private String draggedId;

    OfflineShelfOrder(final List<OfflineStore.Entry> entries) {
        replace(entries);
    }

    List<OfflineStore.Entry> items() {
        return Collections.unmodifiableList(visible);
    }

    boolean replace(final List<OfflineStore.Entry> entries) {
        if (draggedId != null) {
            return false;
        }
        committed = new ArrayList<>(entries);
        visible = new ArrayList<>(entries);
        return true;
    }

    boolean beginDrag(final String id) {
        if (draggedId != null || indexOf(visible, id) < 0) {
            return false;
        }
        draggedId = id;
        return true;
    }

    boolean moveTo(final int target) {
        if (draggedId == null || target < 0 || target >= visible.size()) {
            return false;
        }
        final int from = indexOf(visible, draggedId);
        if (from == target) {
            return false;
        }
        visible.add(target, visible.remove(from));
        return true;
    }

    Move finishDrag() {
        if (draggedId == null) {
            return null;
        }
        final String id = draggedId;
        draggedId = null;
        final int from = indexOf(committed, id);
        final int to = indexOf(visible, id);
        return from == to ? null : new Move(id, committed.get(to).id);
    }

    void cancelDrag() {
        draggedId = null;
        visible = new ArrayList<>(committed);
    }

    Move moveBy(final String id, final int offset) {
        if (draggedId != null) {
            return null;
        }
        final int from = indexOf(committed, id);
        if (from < 0) {
            return null;
        }
        final int to = (int) Math.max(0, Math.min(committed.size() - 1L, (long) from + offset));
        return from == to ? null : new Move(id, committed.get(to).id);
    }

    private static int indexOf(final List<OfflineStore.Entry> entries, final String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    static final class Move {
        final String id;
        private final String targetId;

        private Move(final String id, final String targetId) {
            this.id = id;
            this.targetId = targetId;
        }

        List<OfflineStore.Entry> commit(final OfflineStore store) throws IOException {
            synchronized (store) {
                final List<OfflineStore.Entry> current = store.list();
                final int from = indexOf(current, id);
                final int to = indexOf(current, targetId);
                if (from < 0 || to < 0) {
                    throw new IOException("Saved order changed during drag");
                }
                store.move(id, to - from);
                // No fallible reread after the atomic commit; preserve newly appended entries.
                current.add(to, current.remove(from));
                return current;
            }
        }
    }
}
