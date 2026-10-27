package org.schabi.newpipe.info_list.dialog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.fragment.app.Fragment;

import org.junit.After;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.history.HistoryRecordManager;

import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/** The dialog's actual Rx chain must deliver UI errors only on the main scheduler. */
public class StreamDialogWatchErrorTest {
    @After
    public void resetScheduler() {
        RxAndroidPlugins.reset();
    }

    @Test
    public void errorUiRunsAfterMainDelivery() {
        final TestScheduler io = new TestScheduler();
        final TestScheduler main = new TestScheduler();
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(callable -> main);
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> main);
        final Context context = mock(Context.class);
        final Fragment fragment = mock(Fragment.class);
        when(fragment.getContext()).thenReturn(context);
        when(fragment.requireContext()).thenReturn(context);
        when(fragment.isAdded()).thenReturn(true);
        final StreamInfoItem item = item();

        try (MockedStatic<ErrorUtil> errors = mockStatic(ErrorUtil.class);
             MockedConstruction<HistoryRecordManager> managers =
                     mockConstruction(HistoryRecordManager.class, (manager, construction) ->
                             when(manager.markAsWatched(any())).thenReturn(
                                     Maybe.<Long>error(new IllegalStateException("test failure"))
                                             .subscribeOn(io)))) {
            StreamDialogDefaultEntry.MARK_AS_WATCHED.action.onClick(fragment, item);
            io.triggerActions();
            errors.verifyNoInteractions();

            main.triggerActions();
            errors.verify(() -> ErrorUtil.showSnackbar(eq(context), any(ErrorInfo.class)));
        }
    }

    @Test
    public void detachedFragmentSkipsErrorUiWhenDeliveryArrives() {
        final TestScheduler io = new TestScheduler();
        final TestScheduler main = new TestScheduler();
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(callable -> main);
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> main);
        final Context context = mock(Context.class);
        final Fragment fragment = mock(Fragment.class);
        when(fragment.getContext()).thenReturn(context);
        when(fragment.isAdded()).thenReturn(true);

        try (MockedStatic<ErrorUtil> errors = mockStatic(ErrorUtil.class);
             MockedConstruction<HistoryRecordManager> managers =
                     mockConstruction(HistoryRecordManager.class, (manager, construction) ->
                             when(manager.markAsWatched(any())).thenReturn(
                                     Maybe.<Long>error(new IllegalStateException("test failure"))
                                             .subscribeOn(io)))) {
            StreamDialogDefaultEntry.MARK_AS_WATCHED.action.onClick(fragment, item());
            io.triggerActions();
            when(fragment.isAdded()).thenReturn(false);
            main.triggerActions();

            verify(fragment, never()).requireContext();
            errors.verifyNoInteractions();
        }
    }

    @Test
    public void successfulAndEmptyHistoryResultsDoNotShowErrors() {
        final TestScheduler main = new TestScheduler();
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(callable -> main);
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> main);
        final Context context = mock(Context.class);
        final Fragment fragment = mock(Fragment.class);
        when(fragment.getContext()).thenReturn(context);
        final AtomicReference<Maybe<Long>> result = new AtomicReference<>(Maybe.just(42L));

        try (MockedStatic<ErrorUtil> errors = mockStatic(ErrorUtil.class);
             MockedConstruction<HistoryRecordManager> managers =
                     mockConstruction(HistoryRecordManager.class, (manager, construction) ->
                             when(manager.markAsWatched(any())).thenAnswer(invocation ->
                                     result.get()))) {
            StreamDialogDefaultEntry.MARK_AS_WATCHED.action.onClick(fragment, item());
            main.triggerActions();
            result.set(Maybe.empty());
            StreamDialogDefaultEntry.MARK_AS_WATCHED.action.onClick(fragment, item());
            main.triggerActions();
            errors.verifyNoInteractions();
        }
    }

    private static StreamInfoItem item() {
        return new StreamInfoItem(1, "/watch/test", "Test", StreamType.VIDEO_STREAM);
    }
}
