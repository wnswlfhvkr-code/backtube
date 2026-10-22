package org.schabi.newpipe.offline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Transient shelf choices use saved identities, independently of row positions and tap order. */
final class OfflineShelfSelection {
    private boolean active;
    private final Set<String> ids = new LinkedHashSet<>();

    boolean isActive() {
        return active;
    }

    boolean contains(final String id) {
        return ids.contains(id);
    }

    void enter() {
        active = true;
        ids.clear();
    }

    void toggle(final OfflineStore.Entry entry) {
        if (active && entry.state == OfflineStore.State.READY && !ids.remove(entry.id)) {
            ids.add(entry.id);
        }
    }

    void clear() {
        ids.clear();
    }

    void cancel() {
        active = false;
        clear();
    }

    List<String> snapshot() {
        return new ArrayList<>(ids);
    }

    void restore(final boolean selecting, final List<String> savedIds) {
        cancel();
        active = selecting;
        if (active && savedIds != null) {
            for (final String id : savedIds) {
                if (id != null && !id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
    }

    boolean reconcile(final List<OfflineStore.Entry> entries) {
        final Set<String> available = new HashSet<>();
        for (final OfflineStore.Entry entry : entries) {
            if (entry.state == OfflineStore.State.READY) {
                available.add(entry.id);
            }
        }
        return ids.retainAll(available);
    }
}
