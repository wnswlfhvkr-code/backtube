package us.shandian.giga.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import android.os.Handler;
import android.os.Message;

import androidx.collection.SparseArrayCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import org.junit.Test;

import us.shandian.giga.get.DownloadMission;

public class DownloadNotificationIndexTest {
    @Test
    public void leavingErrorStateRemovesMatchingMissionByIndexAndPreservesOthers()
            throws Exception {
        final DownloadManagerService service = mock(
                DownloadManagerService.class, CALLS_REAL_METHODS);
        final DownloadMission first = mock(DownloadMission.class);
        final DownloadMission second = mock(DownloadMission.class);
        final SparseArrayCompat<DownloadMission> failures = new SparseArrayCompat<>();
        failures.put(1002, first);
        failures.put(1005, second);
        set(service, "mHandler", mock(Handler.class));
        set(service, "mFailedDownloads", failures);
        set(service, "mEchoObservers", new ArrayList<Handler.Callback>());

        final Message message = mock(Message.class);
        message.what = DownloadManagerService.MESSAGE_DELETED;
        message.obj = first;
        handle(service, message);

        assertNull(failures.get(1002));
        assertSame(second, failures.get(1005));
        assertEquals(1, failures.size());
    }

    @Test
    public void messageForUnknownMissionDoesNotClearAnotherFailure() throws Exception {
        final DownloadManagerService service = mock(
                DownloadManagerService.class, CALLS_REAL_METHODS);
        final DownloadMission known = mock(DownloadMission.class);
        final SparseArrayCompat<DownloadMission> failures = new SparseArrayCompat<>();
        failures.put(-1, known);
        set(service, "mHandler", mock(Handler.class));
        set(service, "mFailedDownloads", failures);
        set(service, "mEchoObservers", new ArrayList<Handler.Callback>());

        final Message message = mock(Message.class);
        message.what = DownloadManagerService.MESSAGE_DELETED;
        message.obj = mock(DownloadMission.class);
        handle(service, message);

        assertSame(known, failures.get(-1));
    }

    private static void handle(final DownloadManagerService service, final Message message)
            throws Exception {
        final Method handle = DownloadManagerService.class.getDeclaredMethod(
                "handleMessage", Message.class);
        handle.setAccessible(true);
        handle.invoke(service, message);
    }

    private static void set(final DownloadManagerService service, final String fieldName,
                            final Object value) throws Exception {
        final Field field = DownloadManagerService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
