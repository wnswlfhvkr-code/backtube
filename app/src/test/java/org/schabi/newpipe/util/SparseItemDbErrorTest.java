package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.widget.Toast;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Runs the real sparse-item Rx path with a dummy DAO and synthetic stream info. */
public class SparseItemDbErrorTest {
    private final List<Throwable> uncaughtRxErrors = new ArrayList<>();
    private static final int SERVICE_ID = 7;
    private static final String URL = "https://fixture.invalid/item";

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxJavaPlugins.setErrorHandler(uncaughtRxErrors::add);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(
                ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(
                ignored -> Schedulers.trampoline());
    }

    @After
    public void tearDown() {
        RxAndroidPlugins.reset();
        RxJavaPlugins.reset();
    }

    @Test
    public void databaseFailureNotifiesOnceWithoutEscapingToGlobalRx() {
        final IllegalStateException failure = new IllegalStateException("dummy DAO failure");
        final StreamInfo info = streamInfo();
        final Context context = mock(Context.class);
        final AppDatabase database = mock(AppDatabase.class);
        final StreamDAO dao = mock(StreamDAO.class);
        when(database.streamDAO()).thenReturn(dao);
        when(dao.upsert(any(StreamEntity.class))).thenThrow(failure);
        final AtomicReference<StreamInfo> delivered = new AtomicReference<>();

        try (MockedStatic<Toast> toasts = mockStatic(Toast.class);
             MockedStatic<NewPipeDatabase> databases = mockStatic(NewPipeDatabase.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class);
             MockedStatic<ErrorUtil> errors = mockStatic(ErrorUtil.class)) {
            toasts.when(() -> Toast.makeText(eq(context), anyInt(), eq(Toast.LENGTH_SHORT)))
                    .thenReturn(mock(Toast.class));
            databases.when(() -> NewPipeDatabase.getInstance(context)).thenReturn(database);
            extractor.when(() -> ExtractorHelper.getStreamInfo(SERVICE_ID, URL, false))
                    .thenReturn(Single.just(info));

            SparseItemUtil.fetchStreamInfoAndSaveToDatabase(context, SERVICE_ID, URL,
                    delivered::set);

            assertSame(info, delivered.get());
            verify(dao, times(1)).upsert(any(StreamEntity.class));
            errors.verify(() -> ErrorUtil.createNotification(eq(context),
                    any(ErrorInfo.class)), times(1));
            assertTrue("DB failure must have one owned reporting path", uncaughtRxErrors.isEmpty());
        }
    }

    @Test
    public void successfulDatabaseWriteKeepsCallbackAndDoesNotReportError() {
        final StreamInfo info = streamInfo();
        final Context context = mock(Context.class);
        final AppDatabase database = mock(AppDatabase.class);
        final StreamDAO dao = mock(StreamDAO.class);
        when(database.streamDAO()).thenReturn(dao);
        when(dao.upsert(any(StreamEntity.class))).thenReturn(17L);
        final AtomicReference<StreamInfo> delivered = new AtomicReference<>();

        try (MockedStatic<Toast> toasts = mockStatic(Toast.class);
             MockedStatic<NewPipeDatabase> databases = mockStatic(NewPipeDatabase.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class);
             MockedStatic<ErrorUtil> errors = mockStatic(ErrorUtil.class)) {
            toasts.when(() -> Toast.makeText(eq(context), anyInt(), eq(Toast.LENGTH_SHORT)))
                    .thenReturn(mock(Toast.class));
            databases.when(() -> NewPipeDatabase.getInstance(context)).thenReturn(database);
            extractor.when(() -> ExtractorHelper.getStreamInfo(SERVICE_ID, URL, false))
                    .thenReturn(Single.just(info));

            SparseItemUtil.fetchStreamInfoAndSaveToDatabase(context, SERVICE_ID, URL,
                    delivered::set);

            assertSame(info, delivered.get());
            verify(dao, times(1)).upsert(any(StreamEntity.class));
            errors.verifyNoInteractions();
            assertTrue(uncaughtRxErrors.isEmpty());
        }
    }

    @Test
    public void extractorFailureReportsOnceWithoutWritingToDatabase() {
        final IOException failure = new IOException("dummy extractor failure");
        final Context context = mock(Context.class);
        final AtomicReference<StreamInfo> delivered = new AtomicReference<>();

        try (MockedStatic<Toast> toasts = mockStatic(Toast.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class);
             MockedStatic<ErrorUtil> errors = mockStatic(ErrorUtil.class)) {
            toasts.when(() -> Toast.makeText(eq(context), anyInt(), eq(Toast.LENGTH_SHORT)))
                    .thenReturn(mock(Toast.class));
            extractor.when(() -> ExtractorHelper.getStreamInfo(SERVICE_ID, URL, false))
                    .thenReturn(Single.error(failure));

            SparseItemUtil.fetchStreamInfoAndSaveToDatabase(context, SERVICE_ID, URL,
                    delivered::set);

            assertEquals(null, delivered.get());
            errors.verify(() -> ErrorUtil.createNotification(eq(context),
                    any(ErrorInfo.class)), times(1));
            assertTrue(uncaughtRxErrors.isEmpty());
        }
    }

    private static StreamInfo streamInfo() {
        return new StreamInfo(SERVICE_ID, "fixture", URL,
                StreamType.VIDEO_STREAM, "Synthetic", "", 0);
    }
}
