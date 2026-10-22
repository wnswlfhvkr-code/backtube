package us.shandian.giga.ui.common;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.widget.Toast;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import kotlin.Pair;
import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.Mission;
import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.ui.adapter.MissionAdapter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A rejected keep-file removal must restore its row and tell the user why it remains. */
public class OfflineCancellationFeedbackTest {
    @Test public void retainedManagedOwnerRestoresRowAndShowsBusyFeedback() throws Exception {
        final Context context = mock(Context.class);
        final MissionAdapter adapter = mock(MissionAdapter.class);
        final DownloadManager manager = mock(DownloadManager.class);
        final DownloadMission mission = mock(DownloadMission.class);
        mission.storage = mock(StoredFileHelper.class);
        when(mission.storage.getTag()).thenReturn("offline:generated");
        when(manager.getOfflineMission("generated")).thenReturn(mission);
        final Deleter deleter = new Deleter(mock(View.class), context, adapter, manager,
                mock(DownloadManager.MissionIterator.class), mock(Handler.class));
        final Field items = Deleter.class.getDeclaredField("items");
        items.setAccessible(true);
        items.set(deleter, new ArrayList<>(List.of(new Pair<Mission, Boolean>(mission, false))));
        try (MockedStatic<Toast> notices = mockStatic(Toast.class)) {
            notices.when(() -> Toast.makeText(any(Context.class), anyInt(), anyInt()))
                    .thenReturn(mock(Toast.class));
            final var commit = Deleter.class.getDeclaredMethod("commit");
            commit.setAccessible(true);
            commit.invoke(deleter);
            verify(adapter).applyChanges();
            notices.verify(() -> Toast.makeText(any(Context.class), anyInt(), anyInt()));
        }
    }
}
