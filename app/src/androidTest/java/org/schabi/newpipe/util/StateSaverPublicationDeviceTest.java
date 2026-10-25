package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Uses real Android Bundle and file I/O in a generated cache fixture only. */
@RunWith(AndroidJUnit4.class)
public class StateSaverPublicationDeviceTest {
    @Test
    public void failedSnapshotKeepsRestorableOldStateUntilNewSnapshotIsComplete()
            throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File fixture = new File(context.getCacheDir(),
                "state-publication-fixture-" + UUID.randomUUID());
        assertTrue(fixture.mkdir());
        final Field path = StateSaver.class.getDeclaredField("cacheDirPath");
        path.setAccessible(true);
        final String originalPath = (String) path.get(null);
        path.set(null, fixture.getAbsolutePath());
        try {
            final Bundle oldBundle = new Bundle();
            final SavedState old = StateSaver.tryToSave(false, null, oldBundle,
                    writer(".old", "OLD"));
            assertNotNull(old);

            final Bundle failedBundle = new Bundle();
            assertSame(old, StateSaver.tryToSave(false, old, failedBundle,
                    writer(".new", new FailingValue())));
            assertTrue(new File(old.getPathFileSaved()).exists());
            assertFalse(new File(old.getPathFileSaved().replace(".old", ".new")).exists());
            final AtomicReference<Object> restoredOld = new AtomicReference<>();
            assertNotNull(StateSaver.tryToRestore(failedBundle, reader(restoredOld)));
            assertEquals("OLD", restoredOld.get());

            final Bundle newBundle = new Bundle();
            final SavedState fresh = StateSaver.tryToSave(false, old, newBundle,
                    writer(".new", "NEW"));
            assertNotNull(fresh);
            final AtomicReference<Object> restoredNew = new AtomicReference<>();
            assertNotNull(StateSaver.tryToRestore(newBundle, reader(restoredNew)));
            assertEquals("NEW", restoredNew.get());
            assertFalse(new File(old.getPathFileSaved()).exists());
        } finally {
            path.set(null, originalPath);
            deleteFixture(fixture);
        }
    }

    private static StateSaver.WriteRead writer(final String suffix, final Object value) {
        return new StateSaver.WriteRead() {
            @Override public String generateSuffix() {
                return suffix;
            }
            @Override public void writeTo(final Queue<Object> queue) {
                queue.add(value);
            }
            @Override public void readFrom(final Queue<Object> queue) { }
        };
    }

    private static StateSaver.WriteRead reader(final AtomicReference<Object> destination) {
        return new StateSaver.WriteRead() {
            @Override public String generateSuffix() {
                return ".unused";
            }
            @Override public void writeTo(final Queue<Object> queue) { }
            @Override public void readFrom(final Queue<Object> queue) {
                destination.set(queue.remove());
            }
        };
    }

    private static void deleteFixture(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteFixture(child);
            }
        }
        file.delete();
    }

    private static final class FailingValue implements Serializable {
        private static final long serialVersionUID = 1L;

        private void writeObject(final ObjectOutputStream output) throws IOException {
            output.writeUTF("partial");
            throw new IOException("synthetic serialization failure");
        }
    }
}
