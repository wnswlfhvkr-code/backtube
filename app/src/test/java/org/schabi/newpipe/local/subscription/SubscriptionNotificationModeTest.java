package org.schabi.newpipe.local.subscription;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.subscription.NotificationMode;
import org.schabi.newpipe.database.subscription.SubscriptionDAO;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.util.ExtractorHelper;

/** One subscribed update must perform the follow-up, without contacting a provider. */
public class SubscriptionNotificationModeTest {
    @Test
    public void enablingRunsRememberingOnceAndDisablingDoesNot() {
        final Context context = mock(Context.class);
        final AppDatabase db = mock(AppDatabase.class);
        final SubscriptionDAO dao = mock(SubscriptionDAO.class);
        final SubscriptionEntity entity = new SubscriptionEntity();
        entity.setServiceId(7);
        entity.setUrl("https://fixture.invalid/channel-a");
        final AtomicInteger reads = new AtomicInteger();
        when(db.subscriptionDAO()).thenReturn(dao);
        when(dao.getSubscription(7, entity.getUrl())).thenReturn(Maybe.just(entity));
        emulateModeWrites(dao, entity);

        try (MockedStatic<NewPipeDatabase> database = mockStatic(NewPipeDatabase.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class)) {
            database.when(() -> NewPipeDatabase.getInstance(context)).thenReturn(db);
            extractor.when(() -> ExtractorHelper.getChannelInfo(7, entity.getUrl(), false))
                    .thenReturn(Single.<ChannelInfo>defer(() -> {
                        reads.incrementAndGet();
                        return Single.error(new IOException("synthetic extractor failure"));
                    }));

            final SubscriptionManager manager = new SubscriptionManager(context);
            final var enable = manager.updateNotificationMode(7, entity.getUrl(),
                    NotificationMode.ENABLED);
            assertEquals(0, reads.get()); // cold until subscription
            enable.test().assertComplete().assertNoErrors();
            assertEquals(1, reads.get()); // onErrorComplete policy remains
            assertEquals(NotificationMode.ENABLED, entity.getNotificationMode());
            verify(dao).updateNotificationMode(7, entity.getUrl(), NotificationMode.ENABLED);

            manager.updateNotificationMode(7, entity.getUrl(), NotificationMode.DISABLED)
                    .test().assertComplete().assertNoErrors();
            assertEquals(1, reads.get());
            assertEquals(NotificationMode.DISABLED, entity.getNotificationMode());
        }
    }

    @Test
    public void absentSubscriptionDoesNotStartRemembering() {
        final Context context = mock(Context.class);
        final AppDatabase db = mock(AppDatabase.class);
        final SubscriptionDAO dao = mock(SubscriptionDAO.class);
        when(db.subscriptionDAO()).thenReturn(dao);
        when(dao.getSubscription(7, "missing")).thenReturn(Maybe.empty());
        try (MockedStatic<NewPipeDatabase> database = mockStatic(NewPipeDatabase.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class)) {
            database.when(() -> NewPipeDatabase.getInstance(context)).thenReturn(db);
            new SubscriptionManager(context).updateNotificationMode(7, "missing",
                    NotificationMode.ENABLED).test().assertComplete().assertNoErrors();
            extractor.verifyNoInteractions();
            verify(dao, never()).updateNotificationMode(eq(7), eq("missing"), anyInt());
        }
    }

    @Test
    public void cancellingBaselineFetchDoesNotLeaveNotificationModeEnabled() {
        final Context context = mock(Context.class);
        final AppDatabase db = mock(AppDatabase.class);
        final SubscriptionDAO dao = mock(SubscriptionDAO.class);
        final SubscriptionEntity entity = new SubscriptionEntity();
        entity.setServiceId(7);
        entity.setUrl("https://fixture.invalid/channel-a");
        when(db.subscriptionDAO()).thenReturn(dao);
        when(dao.getSubscription(7, entity.getUrl())).thenReturn(Maybe.just(entity));
        try (MockedStatic<NewPipeDatabase> database = mockStatic(NewPipeDatabase.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class)) {
            database.when(() -> NewPipeDatabase.getInstance(context)).thenReturn(db);
            extractor.when(() -> ExtractorHelper.getChannelInfo(7, entity.getUrl(), false))
                    .thenReturn(Single.<ChannelInfo>never());
            final TestObserver<Void> update = new SubscriptionManager(context)
                    .updateNotificationMode(7, entity.getUrl(), NotificationMode.ENABLED).test();
            update.assertNotComplete();
            update.dispose();
            assertEquals(NotificationMode.DISABLED, entity.getNotificationMode());
            verify(dao, never()).updateNotificationMode(eq(7), eq(entity.getUrl()), anyInt());
        }
    }

    @Test
    public void alreadyEnabledChannelIsNotBaselinedAgain() {
        final Context context = mock(Context.class);
        final AppDatabase db = mock(AppDatabase.class);
        final SubscriptionDAO dao = mock(SubscriptionDAO.class);
        final SubscriptionEntity entity = new SubscriptionEntity();
        entity.setServiceId(7);
        entity.setUrl("https://fixture.invalid/channel-a");
        entity.setNotificationMode(NotificationMode.ENABLED);
        when(db.subscriptionDAO()).thenReturn(dao);
        when(dao.getSubscription(7, entity.getUrl())).thenReturn(Maybe.just(entity));
        emulateModeWrites(dao, entity);
        try (MockedStatic<NewPipeDatabase> database = mockStatic(NewPipeDatabase.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class)) {
            database.when(() -> NewPipeDatabase.getInstance(context)).thenReturn(db);
            new SubscriptionManager(context).updateNotificationMode(7, entity.getUrl(),
                    NotificationMode.ENABLED).test().assertComplete().assertNoErrors();
            extractor.verifyNoInteractions();
            assertEquals(NotificationMode.ENABLED, entity.getNotificationMode());
        }
    }

    @Test
    public void olderPendingEnableCannotOverrideNewerDisable() {
        final Context context = mock(Context.class);
        final AppDatabase db = mock(AppDatabase.class);
        final SubscriptionDAO dao = mock(SubscriptionDAO.class);
        final SubscriptionEntity entity = new SubscriptionEntity();
        entity.setServiceId(7);
        entity.setUrl("https://fixture.invalid/channel-a");
        final SingleSubject<ChannelInfo> pendingBaseline = SingleSubject.create();
        when(db.subscriptionDAO()).thenReturn(dao);
        when(dao.getSubscription(7, entity.getUrl())).thenReturn(Maybe.just(entity));
        emulateModeWrites(dao, entity);
        try (MockedStatic<NewPipeDatabase> database = mockStatic(NewPipeDatabase.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class)) {
            database.when(() -> NewPipeDatabase.getInstance(context)).thenReturn(db);
            extractor.when(() -> ExtractorHelper.getChannelInfo(7, entity.getUrl(), false))
                    .thenReturn(pendingBaseline);
            final SubscriptionManager manager = new SubscriptionManager(context);
            final TestObserver<Void> olderEnable = manager.updateNotificationMode(7,
                    entity.getUrl(), NotificationMode.ENABLED).test();
            olderEnable.assertNotComplete();
            manager.updateNotificationMode(7, entity.getUrl(), NotificationMode.DISABLED)
                    .test().assertComplete().assertNoErrors();
            pendingBaseline.onError(new IOException("synthetic provider failure"));
            olderEnable.assertComplete().assertNoErrors();
            assertEquals(NotificationMode.DISABLED, entity.getNotificationMode());
        }
    }

    @Test
    public void delayedSubscriptionToOlderEnableCannotOverrideNewerDisable() {
        final Context context = mock(Context.class);
        final AppDatabase db = mock(AppDatabase.class);
        final SubscriptionDAO dao = mock(SubscriptionDAO.class);
        final SubscriptionEntity entity = new SubscriptionEntity();
        entity.setServiceId(7);
        entity.setUrl("https://fixture.invalid/channel-a");
        when(db.subscriptionDAO()).thenReturn(dao);
        when(dao.getSubscription(7, entity.getUrl())).thenReturn(Maybe.just(entity));
        emulateModeWrites(dao, entity);
        try (MockedStatic<NewPipeDatabase> database = mockStatic(NewPipeDatabase.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class)) {
            database.when(() -> NewPipeDatabase.getInstance(context)).thenReturn(db);
            final SubscriptionManager manager = new SubscriptionManager(context);
            final var olderEnable = manager.updateNotificationMode(7, entity.getUrl(),
                    NotificationMode.ENABLED);
            manager.updateNotificationMode(7, entity.getUrl(), NotificationMode.DISABLED)
                    .test().assertComplete().assertNoErrors();
            olderEnable.test().assertComplete().assertNoErrors();
            assertEquals(NotificationMode.DISABLED, entity.getNotificationMode());
            extractor.verifyNoInteractions();
        }
    }

    @Test
    public void newerDisableFromAnotherManagerWinsPendingEnable() {
        final Context context = mock(Context.class);
        final AppDatabase db = mock(AppDatabase.class);
        final SubscriptionDAO dao = mock(SubscriptionDAO.class);
        final SubscriptionEntity entity = new SubscriptionEntity();
        entity.setServiceId(7);
        entity.setUrl("https://fixture.invalid/channel-a");
        final SingleSubject<ChannelInfo> pendingBaseline = SingleSubject.create();
        when(db.subscriptionDAO()).thenReturn(dao);
        when(dao.getSubscription(7, entity.getUrl())).thenReturn(Maybe.just(entity));
        emulateModeWrites(dao, entity);
        try (MockedStatic<NewPipeDatabase> database = mockStatic(NewPipeDatabase.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class)) {
            database.when(() -> NewPipeDatabase.getInstance(context)).thenReturn(db);
            extractor.when(() -> ExtractorHelper.getChannelInfo(7, entity.getUrl(), false))
                    .thenReturn(pendingBaseline);
            final TestObserver<Void> olderEnable = new SubscriptionManager(context)
                    .updateNotificationMode(7, entity.getUrl(), NotificationMode.ENABLED).test();
            new SubscriptionManager(context).updateNotificationMode(7, entity.getUrl(),
                    NotificationMode.DISABLED).test().assertComplete().assertNoErrors();
            pendingBaseline.onError(new IOException("synthetic provider failure"));
            olderEnable.assertComplete().assertNoErrors();
            assertEquals(NotificationMode.DISABLED, entity.getNotificationMode());
        }
    }

    @Test
    public void delayedModeUpdateDoesNotOverwriteNewerChannelMetadata() {
        final Context context = mock(Context.class);
        final AppDatabase db = mock(AppDatabase.class);
        final SubscriptionDAO dao = mock(SubscriptionDAO.class);
        final SubscriptionEntity snapshot = new SubscriptionEntity();
        snapshot.setServiceId(7);
        snapshot.setUrl("https://fixture.invalid/channel-a");
        snapshot.setName("old name");
        final String[] storedName = {"old name"};
        final SingleSubject<ChannelInfo> pendingBaseline = SingleSubject.create();
        when(db.subscriptionDAO()).thenReturn(dao);
        when(dao.getSubscription(7, snapshot.getUrl())).thenReturn(Maybe.just(snapshot));
        org.mockito.Mockito.doAnswer(invocation -> {
            storedName[0] = invocation.getArgument(0, SubscriptionEntity.class).getName();
            return 1;
        }).when(dao).update(org.mockito.ArgumentMatchers.any(SubscriptionEntity.class));
        try (MockedStatic<NewPipeDatabase> database = mockStatic(NewPipeDatabase.class);
             MockedStatic<ExtractorHelper> extractor = mockStatic(ExtractorHelper.class)) {
            database.when(() -> NewPipeDatabase.getInstance(context)).thenReturn(db);
            extractor.when(() -> ExtractorHelper.getChannelInfo(7, snapshot.getUrl(), false))
                    .thenReturn(pendingBaseline);
            final TestObserver<Void> enable = new SubscriptionManager(context)
                    .updateNotificationMode(7, snapshot.getUrl(), NotificationMode.ENABLED).test();
            storedName[0] = "new name"; // a concurrent feed metadata refresh
            pendingBaseline.onError(new IOException("synthetic provider failure"));
            enable.assertComplete().assertNoErrors();
            assertEquals("new name", storedName[0]);
            verify(dao).updateNotificationMode(7, snapshot.getUrl(), NotificationMode.ENABLED);
            verify(dao, never()).update(snapshot);
        }
    }

    private static void emulateModeWrites(final SubscriptionDAO dao,
                                          final SubscriptionEntity entity) {
        org.mockito.Mockito.doAnswer(invocation -> {
            entity.setNotificationMode(invocation.getArgument(2, Integer.class));
            return 1;
        }).when(dao).updateNotificationMode(eq(entity.getServiceId()),
                eq(entity.getUrl()), anyInt());
    }
}
