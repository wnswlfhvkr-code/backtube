package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

/** Exercises the real state-cache writer using only an isolated temporary directory. */
public class StateSaverPublicationTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String originalCacheDir;
    private MockedStatic<TextUtils> texts;
    private MockedStatic<Log> logs;

    @Before
    public void setUp() throws Exception {
        final Field path = StateSaver.class.getDeclaredField("cacheDirPath");
        path.setAccessible(true);
        originalCacheDir = (String) path.get(null);
        path.set(null, temporaryFolder.getRoot().getAbsolutePath());
        texts = mockStatic(TextUtils.class);
        texts.when(() -> TextUtils.isEmpty(any())).thenAnswer(call -> {
            final CharSequence value = call.getArgument(0);
            return value == null || value.length() == 0;
        });
        logs = mockStatic(Log.class);
    }

    @After
    public void tearDown() throws Exception {
        final Field path = StateSaver.class.getDeclaredField("cacheDirPath");
        path.setAccessible(true);
        path.set(null, originalCacheDir);
        final Field holder = StateSaver.class.getDeclaredField("STATE_OBJECTS_HOLDER");
        holder.setAccessible(true);
        ((ConcurrentHashMap<?, ?>) holder.get(null)).clear();
        logs.close();
        texts.close();
    }

    @Test
    public void failedSerializationKeepsPreviousGenerationAndRetryPublishesCompleteFile()
            throws Exception {
        final SavedState old = save(null, ".old", "OLD");
        assertNotNull(old);
        assertEquals("OLD", readFirst(old));

        final Bundle replacement = mock(Bundle.class);
        final SavedState failed = save(old, ".new", new FailingValue(), replacement);
        assertSame(old, failed);
        verify(replacement).putParcelable(StateSaver.KEY_SAVED_STATE, old);
        assertEquals("OLD", readFirst(old));
        assertFalse(new File(old.getPathFileSaved().replace(".old", ".new")).exists());

        final SavedState fresh = save(old, ".new", "NEW");
        assertNotNull(fresh);
        assertEquals("NEW", readFirst(fresh));
        assertFalse(new File(old.getPathFileSaved()).exists());
    }

    @Test
    public void failedPublicationKeepsPreviousGeneration() throws Exception {
        final SavedState old = save(null, ".old", "OLD");
        assertNotNull(old);
        // A missing parent makes the destination rename fail without touching user files.
        final Bundle replacement = mock(Bundle.class);
        assertSame(old, save(old, "/missing", "NEW", replacement));
        verify(replacement).putParcelable(StateSaver.KEY_SAVED_STATE, old);
        assertEquals("OLD", readFirst(old));
    }

    @Test
    public void failedStreamCloseKeepsPreviousGeneration() throws Exception {
        final SavedState old = save(null, ".old", "OLD");
        assertNotNull(old);
        try (MockedConstruction<ObjectOutputStream> streams =
                     mockConstruction(ObjectOutputStream.class, (stream, context) ->
                             doThrow(new IOException("synthetic close failure"))
                                     .when(stream).close())) {
            final Bundle replacement = mock(Bundle.class);
            assertSame(old, save(old, ".new", "NEW", replacement));
            verify(replacement).putParcelable(StateSaver.KEY_SAVED_STATE, old);
        }
        assertEquals("OLD", readFirst(old));
    }

    @Test
    public void canceledWriteAndMemoryOnlyRotationPreserveExistingDiskState() throws Exception {
        final SavedState old = save(null, ".old", "OLD");
        assertNotNull(old);
        try {
            StateSaver.tryToSave(false, old, mock(Bundle.class), new StateSaver.WriteRead() {
                @Override public String generateSuffix() {
                    return ".cancel";
                }
                @Override public void writeTo(final Queue<Object> queue) {
                    throw new CancellationException("synthetic cancellation");
                }
                @Override public void readFrom(final Queue<Object> queue) { }
            });
        } catch (final CancellationException expected) {
            // Cancellation happens before a disk write starts.
        }
        assertEquals("OLD", readFirst(old));

        final SavedState memory = StateSaver.tryToSave(true, old, mock(Bundle.class),
                writer(".memory", "MEMORY"));
        assertNotNull(memory);
        assertTrue(memory.getPathFileSaved().isEmpty());
        assertEquals("OLD", readFirst(old));
        assertNull(save(memory, ".failed", new FailingValue()));
    }

    private static SavedState save(final SavedState previous, final String suffix,
                                   final Object value) {
        return save(previous, suffix, value, mock(Bundle.class));
    }

    private static SavedState save(final SavedState previous, final String suffix,
                                   final Object value, final Bundle destination) {
        return StateSaver.tryToSave(false, previous, destination, writer(suffix, value));
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

    private static Object readFirst(final SavedState state) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(
                new FileInputStream(state.getPathFileSaved()))) {
            return ((Queue<?>) input.readObject()).remove();
        }
    }

    private static final class FailingValue implements Serializable {
        private static final long serialVersionUID = 1L;

        private void writeObject(final ObjectOutputStream output) throws IOException {
            output.writeUTF("partial");
            throw new IOException("synthetic serialization failure");
        }
    }
}
