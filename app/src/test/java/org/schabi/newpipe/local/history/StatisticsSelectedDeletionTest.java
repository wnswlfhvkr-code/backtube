package org.schabi.newpipe.local.history;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.stream.StreamStatisticsEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.local.BaseLocalListFragment;
import org.schabi.newpipe.local.LocalItemListAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** The captured row identity must win even when a later snapshot puts B first. */
public class StatisticsSelectedDeletionTest {
    @Before
    public void useSynchronousMainScheduler() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
    }

    @After
    public void resetSchedulers() {
        RxAndroidPlugins.reset();
    }

    @Test
    public void selectedRowIdIsUsedWithoutLookingUpTheCurrentAdapterIndex() throws Exception {
        final StatisticsPlaylistFragment fragment = spy(new StatisticsPlaylistFragment());
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        doReturn(context).when(fragment).getContext();
        doReturn(mock(View.class)).when(fragment).getView();
        final HistoryRecordManager manager = mock(HistoryRecordManager.class);
        final Field managerField = StatisticsPlaylistFragment.class
                .getDeclaredField("recordManager");
        managerField.setAccessible(true);
        managerField.set(fragment, manager);
        final StreamStatisticsEntry selected = new StreamStatisticsEntry(
                mock(StreamEntity.class), 1200, 11, OffsetDateTime.now(), 1);
        final ArrayList<LocalItem> laterSnapshot = new ArrayList<>();
        laterSnapshot.add(new StreamStatisticsEntry(mock(StreamEntity.class),
                0, 22, OffsetDateTime.now(), 1));
        laterSnapshot.add(new StreamStatisticsEntry(selected.getStreamEntity(),
                2000, 11, selected.getLatestAccessDate(), 2));
        final LocalItemListAdapter adapter = mock(LocalItemListAdapter.class);
        when(adapter.getItemsList()).thenReturn(laterSnapshot);
        final Field adapterField = BaseLocalListFragment.class.getDeclaredField("itemListAdapter");
        adapterField.setAccessible(true);
        adapterField.set(fragment, adapter);
        when(manager.deleteStreamHistoryAndState(11)).thenReturn(Completable.never());

        final Method delete = StatisticsPlaylistFragment.class.getDeclaredMethod(
                "deleteEntry", StreamStatisticsEntry.class);
        delete.setAccessible(true);
        delete.invoke(fragment, selected);

        verify(manager).deleteStreamHistoryAndState(11);
        verify(manager, never()).deleteStreamHistoryAndState(22);
    }
}
