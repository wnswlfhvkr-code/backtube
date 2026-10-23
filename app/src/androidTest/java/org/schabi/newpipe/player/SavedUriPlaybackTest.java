package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.activeQueueActivity;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.callOnMain;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.runOnMain;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.waitFor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.offline.OfflineLibraryActivity;
import org.schabi.newpipe.player.SavedPlaybackTestSupport.RejectNetwork;
import org.schabi.newpipe.player.helper.LastPlaybackSessionStore;
import org.schabi.newpipe.util.InfoCache;
import org.schabi.newpipe.util.NavigationHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Normal saved playback intents and permission failures using only generated media. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SavedUriPlaybackTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final CountDownLatch connected = new CountDownLatch(1);
    private PlayerService service;
    private Player player;
    private File localAudio;
    private boolean bound;
    private Boolean previousHistory;
    private Boolean previousResume;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(final ComponentName name, final IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).getService();
            connected.countDown();
        }
        @Override public void onServiceDisconnected(final ComponentName name) {
            service = null;
        }
    };

    @Before public void setUp() throws Exception {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final String historyKey = context.getString(R.string.enable_watch_history_key);
        final String resumeKey = context.getString(R.string.enable_playback_resume_key);
        previousHistory = preferences.contains(historyKey)
                ? preferences.getBoolean(historyKey, false) : null;
        previousResume = preferences.contains(resumeKey)
                ? preferences.getBoolean(resumeKey, false) : null;
        preferences.edit().putBoolean(historyKey, true).putBoolean(resumeKey, true).commit();
        new LastPlaybackSessionStore(
                PreferenceManager.getDefaultSharedPreferences(context)).clear();
        androidx.core.content.ContextCompat.startForegroundService(context,
                new Intent(context, PlayerService.class)
                        .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue(connected.await(10, TimeUnit.SECONDS));
        assertTrue(waitFor(() -> callOnMain(() -> service.getPlayer() != null), 10));
        player = callOnMain(service::getPlayer);
        runOnMain(() -> player.setAutoQueueEnabled(false));
        localAudio = SavedPlaybackTestSupport.createSilentWav(context, 30);
    }

    @After public void tearDown() {
        runOnMain(() -> {
            for (final Stage stage : Stage.values()) {
                if (stage == Stage.PRE_ON_CREATE || stage == Stage.DESTROYED) {
                    continue;
                }
                for (final android.app.Activity activity : new java.util.ArrayList<>(
                        ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(stage))) {
                    if (!activity.isFinishing() && (activity instanceof PlayQueueActivity
                            || activity instanceof MainActivity
                            || activity instanceof OfflineLibraryActivity)) {
                        activity.finish();
                    }
                }
            }
            if (service != null) {
                service.destroyPlayerAndStopService();
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        if (bound) {
            context.unbindService(connection);
        }
        if (localAudio != null) {
            localAudio.delete();
        }
        new LastPlaybackSessionStore(
                PreferenceManager.getDefaultSharedPreferences(context)).clear();
        final SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(context).edit();
        restorePreference(editor, context.getString(R.string.enable_watch_history_key),
                previousHistory);
        restorePreference(editor, context.getString(R.string.enable_playback_resume_key),
                previousResume);
        editor.commit();
    }

    private static void restorePreference(final SharedPreferences.Editor editor, final String key,
                                           final Boolean previous) {
        if (previous == null) {
            editor.remove(key);
        } else {
            editor.putBoolean(key, previous);
        }
    }

    @Test
    public void savedAllQueueUsesServiceIntentAndNextControlWithoutNetwork() throws Exception {
        final org.schabi.newpipe.offline.OfflineLibrary library =
                org.schabi.newpipe.offline.OfflineLibrary.get(context);
        final var first = library.store().create("Generated queue first", "", 0,
                Uri.fromFile(localAudio).toString(), "audio/wav");
        final File secondAudio = SavedPlaybackTestSupport.createSilentWav(context);
        final var second = library.store().create("Generated queue second", "", 0,
                Uri.fromFile(secondAudio).toString(), "audio/wav");
        try (RejectNetwork network = new RejectNetwork()) {
            library.store().copy(first.id, new FileInputStream(localAudio));
            library.store().copy(second.id, new FileInputStream(secondAudio));
            InfoCache.getInstance().clearCache();
            final var all = library.queueAll();
            for (int index = 0; index < all.size(); index++) {
                if (first.localUrl().equals(all.getItem(index).getUrl())) {
                    all.setIndex(index);
                    break;
                }
            }
            assertEquals(first.localUrl(), all.getItem().getUrl());
            runOnMain(() -> NavigationHelper.playOnBackgroundPlayer(context, all, true));
            assertTrue("normal service intent did not play owned first source", waitFor(
                    () -> callOnMain(() -> player.isPlaying()
                            && first.localUrl().equals(player.getVideoUrl())
                            && player.getCurrentMetadata()
                            instanceof org.schabi.newpipe.offline.OfflineMediaTag), 10));
            runOnMain(() -> {
                assertEquals(first.localUrl(), player.getVideoUrl());
                player.getExoPlayer().setRepeatMode(
                        com.google.android.exoplayer2.Player.REPEAT_MODE_ONE);
            });
            InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(context, PlayQueueActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertTrue(waitFor(() -> callOnMain(() ->
                    SavedPlaybackTestSupport.controllerReady(player)), 10));
            runOnMain(() -> {
                assertEquals("next must begin on the first fixture", first.localUrl(),
                        player.getVideoUrl());
                assertTrue(activeQueueActivity().findViewById(R.id.control_forward)
                        .performClick());
            });
            assertTrue("next control did not play owned second source", waitFor(
                    () -> callOnMain(() -> player.isPlaying()
                            && second.localUrl().equals(player.getVideoUrl())
                            && player.getCurrentMetadata()
                            instanceof org.schabi.newpipe.offline.OfflineMediaTag), 10));
            runOnMain(player::pause);
            SavedPlaybackTestSupport.assertControllerPaused(context, player, second.localUrl());
            assertEquals("saved queue requested network metadata", 0, network.requests.get());
            assertTrue("original fixture was removed", localAudio.isFile());
        } finally {
            library.store().delete(first.id);
            library.store().delete(second.id);
            assertTrue(secondAudio.delete());
        }
    }

    @Test
    public void unreadableSavedFileFailsLocallyAndPreservesItsBytes() throws Exception {
        final org.schabi.newpipe.offline.OfflineLibrary library =
                org.schabi.newpipe.offline.OfflineLibrary.get(context);
        final var entry = library.store().create("Generated unreadable source", "", 0,
                Uri.fromFile(localAudio).toString(), "audio/wav");
        final File saved = library.store().file(entry);
        try (RejectNetwork network = new RejectNetwork()) {
            library.store().copy(entry.id, new FileInputStream(localAudio));
            final long bytes = saved.length();
            assertTrue("could not remove fixture readability", saved.setReadable(false, false));
            assertFalse("fixture remains readable", saved.canRead());
            org.schabi.newpipe.util.ExtractorHelper.getStreamInfo(0, entry.localUrl(), false)
                    .test().assertError(IOException.class);
            assertTrue("permission failure removed saved file", saved.isFile());
            assertEquals(bytes, saved.length());
            assertEquals("permission failure requested network", 0, network.requests.get());
        } finally {
            saved.setReadable(true, true);
            library.store().delete(entry.id);
        }
    }

}
