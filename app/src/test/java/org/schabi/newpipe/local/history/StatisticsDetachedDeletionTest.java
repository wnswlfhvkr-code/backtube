package org.schabi.newpipe.local.history;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.StreamStatisticsEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.dialog.InfoItemDialog;
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry;
import org.schabi.newpipe.info_list.dialog.StreamDialogEntry;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.CompletableSubject;

/** Drives the actual delete callback with generated rows and a delayed dummy operation. */
public class StatisticsDetachedDeletionTest {
    @Before
    public void synchronousMainScheduler() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
    }

    @After
    public void resetSchedulers() {
        RxAndroidPlugins.reset();
    }

    @Test
    public void detachedSuccessCannotUseNullContextOrTouchUi() throws Exception {
        final PendingDelete pending = startDelete();
        doReturn(null).when(pending.fragment).getView();
        doReturn(null).when(pending.fragment).getContext();

        try (MockedStatic<Toast> toasts = mockStatic(Toast.class)) {
            toasts.when(() -> Toast.makeText(nullable(Context.class),
                    eq(R.string.one_item_deleted), eq(Toast.LENGTH_SHORT)))
                    .thenReturn(mock(Toast.class));
            pending.operation.onComplete();
            toasts.verifyNoInteractions();
        }
    }

    @Test
    public void recreatedViewDoesNotReceiveOldSuccess() throws Exception {
        final PendingDelete pending = startDelete();
        doReturn(mock(View.class)).when(pending.fragment).getView();

        // The old callback attempts to show a Snackbar on this new view and fails in the
        // host JVM; the fixed callback must return without reaching Android UI code.
        pending.operation.onComplete();
    }

    @Test
    public void detachedErrorReportsOnceWithoutUsingFragmentContext() throws Exception {
        final PendingDelete pending = startDelete();
        doReturn(null).when(pending.fragment).getView();
        doReturn(null).when(pending.fragment).getContext();
        doNothing().when(pending.fragment).showSnackBarError(any(ErrorInfo.class));

        try (MockedStatic<ErrorUtil> errors = mockStatic(ErrorUtil.class)) {
            pending.operation.onError(new IOException("dummy deletion failure"));
            verify(pending.fragment, times(0)).showSnackBarError(any(ErrorInfo.class));
            errors.verify(() -> ErrorUtil.createNotification(eq(pending.context),
                    any(ErrorInfo.class)), times(1));
        }
    }

    @Test
    public void attachedErrorStillUsesExistingSnackbarErrorPath() throws Exception {
        final PendingDelete pending = startDelete();
        doNothing().when(pending.fragment).showSnackBarError(any(ErrorInfo.class));
        pending.operation.onError(new IOException("dummy deletion failure"));
        verify(pending.fragment, times(1)).showSnackBarError(any(ErrorInfo.class));
    }

    @Test
    public void canceledSubscriptionDoesNotDeliverLateUiCallback() throws Exception {
        final PendingDelete pending = startDelete();
        final Field field = StatisticsPlaylistFragment.class.getDeclaredField("disposables");
        field.setAccessible(true);
        ((CompositeDisposable) field.get(pending.fragment)).clear();
        assertTrue(!pending.operation.hasObservers());
        pending.operation.onComplete();
    }

    @Test
    public void dialogClickAfterViewDetachDoesNotStartDeletion() throws Exception {
        final StatisticsPlaylistFragment fragment = spy(new StatisticsPlaylistFragment());
        doReturn(null).when(fragment).getView();
        doReturn(null).when(fragment).getContext();
        final HistoryRecordManager manager = mock(HistoryRecordManager.class);
        final Field managerField = StatisticsPlaylistFragment.class
                .getDeclaredField("recordManager");
        managerField.setAccessible(true);
        managerField.set(fragment, manager);
        final StreamStatisticsEntry selected = new StreamStatisticsEntry(
                mock(StreamEntity.class), 1200, 11, OffsetDateTime.now(), 1);
        final Method delete = StatisticsPlaylistFragment.class.getDeclaredMethod(
                "deleteEntry", StreamStatisticsEntry.class);
        delete.setAccessible(true);

        delete.invoke(fragment, selected);

        verifyNoInteractions(manager);
    }

    @Test
    public void oldDialogClickAfterViewRecreationDoesNotDeleteOnNewScreen()
            throws Exception {
        final StatisticsPlaylistFragment fragment = spy(new StatisticsPlaylistFragment());
        final Context context = mock(Context.class);
        final FragmentActivity activity = mock(FragmentActivity.class);
        doReturn(context).when(fragment).getContext();
        doReturn(activity).when(fragment).getActivity();
        final View oldView = mock(View.class);
        doReturn(oldView).when(fragment).getView();
        final HistoryRecordManager manager = mock(HistoryRecordManager.class);
        when(manager.deleteStreamHistoryAndState(11)).thenReturn(
                io.reactivex.rxjava3.core.Completable.never());
        final Field managerField = StatisticsPlaylistFragment.class
                .getDeclaredField("recordManager");
        managerField.setAccessible(true);
        managerField.set(fragment, manager);
        final StreamStatisticsEntry selected = mock(StreamStatisticsEntry.class);
        when(selected.toStreamInfoItem()).thenReturn(mock(StreamInfoItem.class));
        when(selected.getStreamId()).thenReturn(11L);

        try (MockedConstruction<InfoItemDialog.Builder> builders =
                     mockConstruction(InfoItemDialog.Builder.class, (builder, invocation) -> {
                         when(builder.addEntry(StreamDialogDefaultEntry.DELETE))
                                 .thenReturn(builder);
                         when(builder.setAction(any(), any())).thenReturn(builder);
                         when(builder.create()).thenReturn(mock(InfoItemDialog.class));
                     })) {
            final Method show = StatisticsPlaylistFragment.class.getDeclaredMethod(
                    "showInfoItemDialog", StreamStatisticsEntry.class);
            show.setAccessible(true);
            show.invoke(fragment, selected);
            final ArgumentCaptor<StreamDialogEntry.StreamDialogEntryAction> action =
                    ArgumentCaptor.forClass(StreamDialogEntry.StreamDialogEntryAction.class);
            verify(builders.constructed().get(0)).setAction(
                    eq(StreamDialogDefaultEntry.DELETE), action.capture());

            final View newView = mock(View.class);
            doReturn(newView).when(fragment).getView();
            action.getValue().onClick(fragment, mock(StreamInfoItem.class));
            verify(manager, never()).deleteStreamHistoryAndState(11);

            show.invoke(fragment, selected);
            final ArgumentCaptor<StreamDialogEntry.StreamDialogEntryAction> freshAction =
                    ArgumentCaptor.forClass(StreamDialogEntry.StreamDialogEntryAction.class);
            verify(builders.constructed().get(1)).setAction(
                    eq(StreamDialogDefaultEntry.DELETE), freshAction.capture());
            freshAction.getValue().onClick(fragment, mock(StreamInfoItem.class));
            verify(manager, times(1)).deleteStreamHistoryAndState(11);
        }
    }

    private static PendingDelete startDelete() throws Exception {
        final StatisticsPlaylistFragment fragment = spy(new StatisticsPlaylistFragment());
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        final View owner = mock(View.class);
        doReturn(context).when(fragment).getContext();
        doReturn(owner).when(fragment).getView();
        final HistoryRecordManager manager = mock(HistoryRecordManager.class);
        final CompletableSubject operation = CompletableSubject.create();
        when(manager.deleteStreamHistoryAndState(11)).thenReturn(operation);
        final Field managerField = StatisticsPlaylistFragment.class
                .getDeclaredField("recordManager");
        managerField.setAccessible(true);
        managerField.set(fragment, manager);
        final StreamStatisticsEntry selected = new StreamStatisticsEntry(
                mock(StreamEntity.class), 1200, 11, OffsetDateTime.now(), 1);
        final Method delete = StatisticsPlaylistFragment.class.getDeclaredMethod(
                "deleteEntry", StreamStatisticsEntry.class);
        delete.setAccessible(true);
        delete.invoke(fragment, selected);
        assertTrue(operation.hasObservers());
        verify(manager).deleteStreamHistoryAndState(11);
        return new PendingDelete(fragment, context, operation);
    }

    private static final class PendingDelete {
        private final StatisticsPlaylistFragment fragment;
        private final Context context;
        private final CompletableSubject operation;

        private PendingDelete(final StatisticsPlaylistFragment fragment, final Context context,
                              final CompletableSubject operation) {
            this.fragment = fragment;
            this.context = context;
            this.operation = operation;
        }
    }
}
