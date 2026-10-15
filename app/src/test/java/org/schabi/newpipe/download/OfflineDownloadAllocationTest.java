package org.schabi.newpipe.download;

import android.content.Context;
import android.widget.RadioGroup;
import android.widget.SeekBar;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.databinding.DownloadDialogBinding;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real writable-file boundary; no user files, Android UI or transport is opened. */
public class OfflineDownloadAllocationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @After public void reset() throws Exception {
        final Field field = OfflineLibrary.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test public void completedSavedBytesNeverReachWritableDownloadBoundary() throws Exception {
        reset();
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.getRoot());
        final OfflineStore store = OfflineLibrary.get(context).store();
        final OfflineStore.Entry saved = store.create("Generated", "https://example.invalid/one",
                0, "file:///generated/input.wav", "audio/wav");
        store.copy(saved.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.getTag()).thenReturn("offline:" + saved.id);
        when(storage.canWrite()).thenReturn(true);
        when(storage.length()).thenReturn(3L);
        doAnswer(call -> {
            try (RandomAccessFile file = new RandomAccessFile(store.file(saved), "rw")) {
                file.setLength(0);
            }
            return null;
        }).when(storage).truncate();
        final DownloadDialog dialog = mock(DownloadDialog.class, CALLS_REAL_METHODS);
        set(dialog, "context", context);
        // An unselected group stops after the file boundary; no stream/transport is needed.
        final DownloadDialogBinding binding = mock(DownloadDialogBinding.class);
        set(binding, "threads", mock(SeekBar.class));
        final RadioGroup group = mock(RadioGroup.class);
        when(group.getCheckedRadioButtonId()).thenReturn(-1);
        set(binding, "videoAudioGroup", group);
        set(dialog, "dialogBinding", binding);
        final var proceed = DownloadDialog.class.getDeclaredMethod("continueSelectedDownload",
                StoredFileHelper.class);
        proceed.setAccessible(true);
        proceed.invoke(dialog, storage);
        assertEquals("selecting an existing save must preserve complete bytes",
                3, store.file(saved).length());
        verify(storage, never()).canWrite();
        verify(storage, never()).truncate();
    }

    private static void set(final Object object, final String name, final Object value)
            throws Exception {
        final Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
