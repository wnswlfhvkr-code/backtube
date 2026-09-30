package org.schabi.newpipe.player.playqueue;

import java.util.List;

/** Offline page boundary fixture: fetching supplies exactly one final page. */
public final class PagedTestQueue extends PlayQueue {
    private final PlayQueueItem next;
    private boolean complete;

    public PagedTestQueue(final PlayQueueItem first, final PlayQueueItem next) {
        super(0, List.of(first));
        this.next = next;
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

    @Override
    public void fetch() {
        if (!complete) {
            complete = true;
            append(List.of(next));
        }
    }
}
