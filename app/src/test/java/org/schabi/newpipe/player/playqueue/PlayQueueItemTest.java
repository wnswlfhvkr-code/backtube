package org.schabi.newpipe.player.playqueue;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class PlayQueueItemTest {

    public static final String URL = "MY_URL";

    @Test
    public void oldSerializedQueueItemDefaultsToNoExplicitTimestamp() throws Exception {
        try (java.io.ObjectInputStream input = new java.io.ObjectInputStream(
                java.util.Objects.requireNonNull(getClass().getResourceAsStream(
                        "/player/legacy-play-queue-item.bin")))) {
            final PlayQueueItem item = (PlayQueueItem) input.readObject();
            assertEquals("https://example.invalid/legacy", item.getUrl());
            assertEquals(PlayQueueItem.RECOVERY_UNSET, item.getExplicitStartPositionMillis());
            assertEquals(6054959748728707165L,
                    java.io.ObjectStreamClass.lookup(PlayQueueItem.class).getSerialVersionUID());
        }
    }

    @Test
    public void equalsMustNotBeOverloaded() {
        final PlayQueueItem a = PlayQueueTest.makeItemWithUrl(URL);
        final PlayQueueItem b = PlayQueueTest.makeItemWithUrl(URL);
        assertEquals(a, a);
        assertNotEquals(a, b); // they should compare different even if they have the same data
    }
}
