package org.schabi.newpipe.local.subscription;

import static org.junit.Assert.assertEquals;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.subscription.NotificationMode;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.testUtil.TestDatabase;

import java.util.concurrent.TimeUnit;

/** Real Room boundary with test-only rows and no extractor requests. */
public class SubscriptionNotificationModeDeviceTest {
    private AppDatabase database;
    private SubscriptionManager manager;

    @Before
    public void setUp() {
        database = TestDatabase.Companion.createReplacingNewPipeDatabase();
        manager = new SubscriptionManager(ApplicationProvider.getApplicationContext());
    }

    @After
    public void tearDown() {
        NewPipeDatabase.close();
    }

    @Test
    public void disablingExistingRowWritesToRoomAndMissingRowCompletes() {
        final SubscriptionEntity entity = new SubscriptionEntity();
        entity.setServiceId(7);
        entity.setUrl("https://fixture.invalid/channel-a");
        entity.setNotificationMode(NotificationMode.ENABLED);
        manager.insertSubscription(entity);

        manager.updateNotificationMode(7, entity.getUrl(), NotificationMode.DISABLED)
                .test().awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors();
        assertEquals(NotificationMode.DISABLED, database.subscriptionDAO()
                .getSubscription(7, entity.getUrl()).blockingGet().getNotificationMode());

        manager.updateNotificationMode(7, "https://fixture.invalid/missing",
                NotificationMode.DISABLED).test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete().assertNoErrors();
        assertEquals(1, database.subscriptionDAO().getAll().blockingFirst().size());
    }

    @Test
    public void modeColumnUpdateKeepsRefreshedMetadataAndExactUrlIdentity() {
        final SubscriptionEntity target = new SubscriptionEntity();
        target.setServiceId(7);
        target.setUrl("https://fixture.invalid/channel_%");
        target.setName("old name");
        manager.insertSubscription(target);

        final SubscriptionEntity other = new SubscriptionEntity();
        other.setServiceId(7);
        other.setUrl("https://fixture.invalid/channel_AB");
        other.setName("other name");
        manager.insertSubscription(other);

        final SubscriptionEntity refreshed = database.subscriptionDAO()
                .getSubscription(7, target.getUrl()).blockingGet();
        refreshed.setName("new name");
        database.subscriptionDAO().update(refreshed);
        assertEquals(1, database.subscriptionDAO().updateNotificationMode(7,
                target.getUrl(), NotificationMode.ENABLED));

        final SubscriptionEntity targetAfter = database.subscriptionDAO()
                .getSubscription(7, target.getUrl()).blockingGet();
        final SubscriptionEntity otherAfter = database.subscriptionDAO()
                .getSubscription(7, other.getUrl()).blockingGet();
        assertEquals("new name", targetAfter.getName());
        assertEquals(NotificationMode.ENABLED, targetAfter.getNotificationMode());
        assertEquals("other name", otherAfter.getName());
        assertEquals(NotificationMode.DISABLED, otherAfter.getNotificationMode());
    }
}
