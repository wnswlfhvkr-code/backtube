package org.schabi.newpipe.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.DialogInterface;
import android.content.Intent;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.fragments.list.playlist.Batch10TestActivity;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class MobileDataDownloadConfirmationDeviceTest {
    @Test public void mobileCancelAndBackDoNotAcceptRequest() {
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final var activity = (Batch10TestActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), Batch10TestActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger declined = new AtomicInteger();
        final AtomicReference<AlertDialog> shown = new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(() -> shown.set(
                    MobileDataDownloadConfirmation.request(activity,
                            DownloadNetworkPolicy.Type.MOBILE,
                            () -> DownloadNetworkPolicy.Type.MOBILE,
                            approved -> accepted.incrementAndGet(), declined::incrementAndGet)));
            assertNotNull(shown.get());
            instrumentation.runOnMainSync(() -> shown.get()
                    .getButton(DialogInterface.BUTTON_NEGATIVE).performClick());
            instrumentation.waitForIdleSync();
            assertEquals(0, accepted.get());
            assertEquals(1, declined.get());

            instrumentation.runOnMainSync(() -> shown.set(
                    MobileDataDownloadConfirmation.request(activity,
                            DownloadNetworkPolicy.Type.MOBILE,
                            () -> DownloadNetworkPolicy.Type.MOBILE,
                            approved -> accepted.incrementAndGet(), declined::incrementAndGet)));
            instrumentation.runOnMainSync(() -> shown.get().cancel());
            instrumentation.waitForIdleSync();
            assertEquals(0, accepted.get());
            assertEquals(2, declined.get());
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void mobilePositiveApprovesOnlyThatRequest() {
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final var activity = (Batch10TestActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), Batch10TestActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final AtomicBoolean approved = new AtomicBoolean();
        final AtomicInteger declined = new AtomicInteger();
        final AtomicReference<AlertDialog> shown = new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(() -> shown.set(
                    MobileDataDownloadConfirmation.request(activity,
                            DownloadNetworkPolicy.Type.MOBILE,
                            () -> DownloadNetworkPolicy.Type.MOBILE,
                            approved::set, declined::incrementAndGet)));
            assertNotNull(shown.get());
            instrumentation.runOnMainSync(() -> shown.get()
                    .getButton(DialogInterface.BUTTON_POSITIVE).performClick());
            instrumentation.waitForIdleSync();
            assertTrue(approved.get());
            assertEquals(0, declined.get());
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
