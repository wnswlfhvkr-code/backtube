package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.SwitchCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.fragments.list.BaseListFragment;
import org.schabi.newpipe.fragments.list.videos.RelatedItemsFragment;
import org.schabi.newpipe.info_list.InfoListAdapter;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediasource.FailedMediaSource;
import org.schabi.newpipe.util.DataSaver;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.image.ImageStrategy;
import org.schabi.newpipe.util.image.PreferredImageQuality;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.helper.LastPlaybackSessionStore;
import org.schabi.newpipe.player.helper.RecommendationExclusions;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.PagedTestQueue;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.util.InfoCache;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.SingleSubject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runtime coverage for personal playback controls using a generated local WAV only.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class PersonalPlaybackTest {
    private static final long SERVICE_TIMEOUT_SECONDS = 10;
    private static final long PLAYBACK_TIMEOUT_SECONDS = 5;
    private static final String SCREENSHOT_DIRECTORY = "personal-playback-test";

    private final Context context = InstrumentationRegistry.getInstrumentation()
            .getTargetContext();
    private final CountDownLatch serviceConnected = new CountDownLatch(1);

    private PlayerService service;
    private org.schabi.newpipe.player.Player player;
    private boolean bound;
    private File localAudio;
    private final String restartPhase = InstrumentationRegistry.getArguments()
            .getString("session_restart_phase", "");

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).getService();
            serviceConnected.countDown();
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            service = null;
        }
    };

    @Before
    public void setUp() throws Exception {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(context.getString(R.string.data_saver_key), false)
                .putString(context.getString(R.string.audio_quality_key), DataSaver.BALANCED)
                .commit();
        final Intent startIntent = new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true);
        androidx.core.content.ContextCompat.startForegroundService(context, startIntent);

        final Intent bindIntent = new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION);
        bound = context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        assertTrue("could not bind PlayerService", bound);
        assertTrue("PlayerService did not connect", serviceConnected.await(
                SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue("PlayerService did not create Player", waitFor(
                () -> callOnMain(() -> service != null && service.getPlayer() != null),
                SERVICE_TIMEOUT_SECONDS));
        player = callOnMain(service::getPlayer);
        runOnMain(() -> {
            player.setAutoQueueEnabled(false);
            if (!"restore".equals(restartPhase)) {
                player.clearRecommendationExclusions();
            }
            initPlayerForLocalAudio();
        });
        if (!"restore".equals(restartPhase)) {
            new LastPlaybackSessionStore(
                    PreferenceManager.getDefaultSharedPreferences(context)).clear();
        }
        localAudio = createSilentWav();
    }

    @After
    public void tearDown() {
        finishQueueActivity();
        if (service != null) {
            runOnMain(service::destroyPlayerAndStopService);
        }
        if (bound) {
            context.unbindService(serviceConnection);
            bound = false;
        }
        if (localAudio != null) {
            localAudio.delete();
        }
        if (!"seed".equals(restartPhase)) {
            new LastPlaybackSessionStore(
                    PreferenceManager.getDefaultSharedPreferences(context)).clear();
        }
        // Flush apply() writes before the test runner exits or the next phase force-stops the app.
        PreferenceManager.getDefaultSharedPreferences(context).edit().commit();
        for (final String id : List.of("offline-A", "offline-B", "offline-C")) {
            InfoCache.getInstance().removeInfo(1, id, InfoCache.Type.STREAM);
        }
    }

    @Test
    public void savedOfflineCopyPlaysWithoutInfoCacheAndKeepsSleepTimerOnNetworkReturn()
            throws Exception {
        final org.schabi.newpipe.offline.OfflineLibrary library =
                org.schabi.newpipe.offline.OfflineLibrary.get(context);
        final org.schabi.newpipe.offline.OfflineStore.Entry entry = library.store().create(
                "Generated offline WAV", "https://example.invalid/owned-recording", 0,
                Uri.fromFile(localAudio).toString(), "audio/wav");
        try {
            library.store().copy(entry.id, new FileInputStream(localAudio));
            InfoCache.getInstance().clearCache();
            runOnMain(() -> {
                final Method init = org.schabi.newpipe.player.Player.class.getDeclaredMethod(
                        "initPlayback", org.schabi.newpipe.player.playqueue.PlayQueue.class,
                        boolean.class);
                init.setAccessible(true);
                init.invoke(player, library.queue(entry), true);
            });
            assertTrue("saved local source did not play", waitFor(() -> callOnMain(() ->
                    player.isPlaying() && player.getCurrentMetadata()
                            instanceof org.schabi.newpipe.offline.OfflineMediaTag), 10));
            runOnMain(() -> {
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putBoolean(context.getString(R.string.data_saver_key), true).commit();
                final java.lang.reflect.Field quality = org.schabi.newpipe.player.Player.class
                        .getDeclaredField("observedAudioQuality");
                quality.setAccessible(true);
                quality.set(player, "offline-network-change-test");
                player.setSleepTimer(1200);
                final Method refresh = org.schabi.newpipe.player.Player.class
                        .getDeclaredMethod("refreshNetworkAudioQuality");
                refresh.setAccessible(true);
                refresh.invoke(player);
                assertEquals("offline-network-change-test", quality.get(player));
                assertTrue(player.getSleepTimerRemainingMillis() > 0);
            });
            assertTrue("offline sleep timer did not pause", waitFor(() -> callOnMain(() ->
                    !player.getPlayWhenReady() && player.getSleepTimerRemainingMillis() == 0), 5));
            assertEquals(entry.localUrl(), callOnMain(player::getVideoUrl));
        } finally {
            library.store().delete(entry.id);
        }
    }

    @Test
    public void savedOfflineShelfImportsGeneratedFile() throws Exception {
        final org.schabi.newpipe.offline.OfflineLibrary library =
                org.schabi.newpipe.offline.OfflineLibrary.get(context);
        final String origin = "https://example.invalid/generated-shelf-test";
        final android.app.Activity shelf = InstrumentationRegistry.getInstrumentation()
                .startActivitySync(new Intent(context,
                        org.schabi.newpipe.offline.OfflineLibraryActivity.class)
                        .setData(Uri.fromFile(localAudio)).putExtra("title", "Generated shelf WAV")
                        .putExtra("origin", origin).putExtra("service", 0)
                        .putExtra("mime", "audio/wav").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            assertTrue("shelf import did not complete", waitFor(() -> callOnMain(() ->
                    library.store().find(0, origin) != null), 15));
            assertTrue("shelf did not show saved playback action", waitFor(() ->
                    findNodeByText(context.getString(R.string.offline_play)) != null, 10));
            captureScreen("offline-shelf-generated.png");
            clickAccessibilityText(context.getString(R.string.offline_play));
            assertTrue("shelf action did not start local playback", waitFor(() -> callOnMain(() ->
                    player.isPlaying() && player.getCurrentMetadata()
                            instanceof org.schabi.newpipe.offline.OfflineMediaTag), 10));
            assertTrue("import changed original file", localAudio.isFile());
        } finally {
            final org.schabi.newpipe.offline.OfflineStore.Entry saved =
                    library.store().find(0, origin);
            if (saved != null) {
                library.store().delete(saved.id);
            }
            runOnMain(shelf::finish);
        }
    }

    @Test
    public void savedOfflineLifecycleAcrossProcessRestart() throws Exception {
        org.junit.Assume.assumeTrue(!restartPhase.isEmpty());
        final org.schabi.newpipe.offline.OfflineLibrary library =
                org.schabi.newpipe.offline.OfflineLibrary.get(context);
        final android.content.SharedPreferences fixture = context.getSharedPreferences(
                "offline-lifecycle-test", Context.MODE_PRIVATE);
        if ("seed".equals(restartPhase)) {
            final org.schabi.newpipe.offline.OfflineStore.Entry saved = library.importUri(
                    Uri.fromFile(localAudio), "Generated saved WAV", "", 0, "audio/wav");
            final org.schabi.newpipe.offline.OfflineStore.Entry expired = library.importUri(
                    Uri.fromFile(localAudio), "Generated expired WAV", "", 0, "audio/wav");
            assertTrue("asynchronous save did not finish", waitFor(() -> callOnMain(() ->
                    library.store().get(saved.id).state
                            == org.schabi.newpipe.offline.OfflineStore.State.READY
                    && library.store().get(expired.id).state
                            == org.schabi.newpipe.offline.OfflineStore.State.READY), 15));
            assertTrue("saving removed original file", localAudio.isFile());
            new LastPlaybackSessionStore(PreferenceManager.getDefaultSharedPreferences(context))
                    .save(library.queue(saved), 1000, Player.REPEAT_MODE_ONE);
            fixture.edit().putString("saved", saved.id).putString("expired", expired.id)
                    .putInt("seedPid", android.os.Process.myPid()).commit();
            // Deterministic elapsed-retention fixture, re-read only in the next process.
            final File metadata = new File(context.getFilesDir(),
                    "offline/" + expired.id + ".properties");
            final java.util.Properties properties = new java.util.Properties();
            try (FileInputStream input = new FileInputStream(metadata)) {
                properties.load(input);
            }
            properties.setProperty("expires", "1");
            try (FileOutputStream output = new FileOutputStream(metadata)) {
                properties.store(output, "generated expiry fixture");
                output.getFD().sync();
            }
            return;
        }
        assertTrue("test requires a different app process",
                fixture.getInt("seedPid", -1) != android.os.Process.myPid());
        assertEquals("airplane mode must be enabled before restore", "1", shellCommand(
                "settings get global airplane_mode_on").trim());
        final String savedId = fixture.getString("saved", "");
        final String expiredId = fixture.getString("expired", "");
        final org.schabi.newpipe.offline.OfflineStore.Entry saved = library.store().get(savedId);
        final org.schabi.newpipe.offline.OfflineStore.Entry expired =
                library.store().get(expiredId);
        assertTrue("completed save missing after restart", saved != null);
        assertEquals(org.schabi.newpipe.offline.OfflineStore.State.EXPIRED, expired.state);
        assertFalse("expired media bytes retained", library.store().file(expired).exists());
        assertNull("expired item still playable", library.source(library.queue(expired).getItem()));
        final LastPlaybackSessionStore.Snapshot snapshot = new LastPlaybackSessionStore(
                PreferenceManager.getDefaultSharedPreferences(context)).load();
        assertTrue("saved queue lost across restart", snapshot != null);
        InfoCache.getInstance().clearCache();
        runOnMain(() -> {
            final Method init = org.schabi.newpipe.player.Player.class.getDeclaredMethod(
                    "initPlayback", org.schabi.newpipe.player.playqueue.PlayQueue.class,
                    boolean.class);
            init.setAccessible(true);
            init.invoke(player, snapshot.getQueue(), true);
            player.getExoPlayer().setRepeatMode(Player.REPEAT_MODE_ONE);
        });
        assertTrue("restarted offline playback did not advance", waitFor(() -> callOnMain(() ->
                player.isPlaying() && player.getCurrentMetadata()
                        instanceof org.schabi.newpipe.offline.OfflineMediaTag
                        && player.getExoPlayer().getCurrentPosition() > 1000), 15));
        runOnMain(() -> player.setSleepTimer(1500));
        assertTrue("offline timer did not pause playback", waitFor(() -> callOnMain(() ->
                !player.getPlayWhenReady() && player.getSleepTimerRemainingMillis() == 0), 8));
        assertEquals(saved.localUrl(), callOnMain(player::getVideoUrl));
        runOnMain(player::play);
        assertTrue(waitFor(() -> callOnMain(player::isPlaying), 5));
        final File savedFile = library.store().file(saved);
        library.store().delete(saved.id);
        assertNull(library.store().get(saved.id));
        runOnMain(service::destroyPlayerAndStopService);
        assertTrue("deleted bytes not removed after reader release", waitFor(
                () -> !savedFile.exists(), 10));
        library.store().delete(expired.id);
        fixture.edit().clear().commit();
    }

    @Test
    public void savedOfflineQueueRestoresWithoutRemoteMetadata() throws Exception {
        final org.schabi.newpipe.offline.OfflineLibrary library =
                org.schabi.newpipe.offline.OfflineLibrary.get(context);
        final org.schabi.newpipe.offline.OfflineStore.Entry entry = library.store().create(
                "Generated restore WAV", "", 0, Uri.fromFile(localAudio).toString(), "audio/wav");
        final android.content.SharedPreferences preferences = context.getSharedPreferences(
                "offline-restore-test", Context.MODE_PRIVATE);
        try {
            library.store().copy(entry.id, new FileInputStream(localAudio));
            new LastPlaybackSessionStore(preferences).save(library.queue(entry), 1500,
                    com.google.android.exoplayer2.Player.REPEAT_MODE_OFF);
            final LastPlaybackSessionStore.Snapshot snapshot =
                    new LastPlaybackSessionStore(preferences).load();
            assertTrue(snapshot != null);
            InfoCache.getInstance().clearCache();
            runOnMain(() -> {
                final Method init = org.schabi.newpipe.player.Player.class.getDeclaredMethod(
                        "initPlayback", org.schabi.newpipe.player.playqueue.PlayQueue.class,
                        boolean.class);
                init.setAccessible(true);
                init.invoke(player, snapshot.getQueue(), false);
            });
            assertTrue("restored saved source never prepared", waitFor(() -> callOnMain(() ->
                    player.getCurrentMetadata()
                            instanceof org.schabi.newpipe.offline.OfflineMediaTag), 10));
            assertFalse(callOnMain(player::getPlayWhenReady));
            assertEquals(entry.localUrl(), callOnMain(player::getVideoUrl));
        } finally {
            preferences.edit().clear().commit();
            library.store().delete(entry.id);
        }
    }

    @Test
    public void songSelectionPlaysImmediatelyAndPlayerSwitchKeepsPause() throws Exception {
        final String autoplayKey = context.getString(R.string.autoplay_key);
        final String original = player.getPrefs().getString(autoplayKey,
                context.getString(R.string.autoplay_value));
        final String commentsKey = context.getString(R.string.show_comments_key);
        final boolean showComments = player.getPrefs().getBoolean(commentsKey, true);
        try {
            startOfflineRecommendationChain(false);
            runOnMain(() -> {
                player.pause();
                player.getPrefs().edit().putString(autoplayKey,
                        context.getString(R.string.autoplay_never_key))
                        .putBoolean(commentsKey, false).commit();
            });
            final MainActivity activity = (MainActivity) InstrumentationRegistry
                    .getInstrumentation().startActivitySync(new Intent(context, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMain(() -> NavigationHelper.openVideoDetailFragment(activity,
                    activity.getSupportFragmentManager(), 1, "offline-C", "offline-C",
                    null, false));
            assertTrue("passive details did not load", waitFor(() -> callOnMain(() -> {
                final android.widget.TextView title = activity.findViewById(
                        R.id.detail_video_title_view);
                return title != null && "offline-C".contentEquals(title.getText());
            }), 5));
            assertTrue("opening details changed the paused queue", callOnMain(
                    () -> !player.getPlayWhenReady()
                            && "offline-A".equals(player.getVideoUrl())));
            runOnMain(() -> NavigationHelper.openVideoDetailFragment(activity,
                    activity.getSupportFragmentManager(), 1, "offline-B", "offline-B",
                    null, false, true));
            assertTrue("song selection required an extra play-button press", waitFor(
                    () -> callOnMain(() -> player.isPlaying()
                            && "offline-B".equals(player.getVideoUrl())), 10));
            captureScreen("backtube-selection-autoplay.png");
            runOnMain(() -> {
                player.pause();
                player.getExoPlayer().seekTo(1200);
                NavigationHelper.openVideoDetailFragment(activity,
                        activity.getSupportFragmentManager(), 1, "offline-B", "offline-B",
                        player.getPlayQueue(), true);
            });
            assertTrue("switching a paused player resumed playback", waitFor(() -> callOnMain(
                    () -> !player.getPlayWhenReady()
                            && player.getExoPlayer().getPlaybackState() == Player.STATE_READY
                            && Math.abs(player.getExoPlayer().getCurrentPosition() - 1200) < 150),
                    5));
            runOnMain(() -> NavigationHelper.openVideoDetailFragment(activity,
                    activity.getSupportFragmentManager(), 1, "offline-C", "offline-C",
                    null, false, true));
            assertTrue("selection from a paused main player did not start", waitFor(
                    () -> callOnMain(() -> player.isPlaying()
                            && "offline-C".equals(player.getVideoUrl())), 10));
            runOnMain(() -> {
                player.pause();
                NavigationHelper.playOnMainPlayer((Context) activity,
                        new SinglePlayQueue(new StreamInfoItem(1, "offline-A", "offline-A",
                                StreamType.AUDIO_STREAM)), false);
            });
            assertTrue("explicit play through a stream intent did not start", waitFor(
                    () -> callOnMain(() -> player.isPlaying()
                            && "offline-A".equals(player.getVideoUrl())), 10));
        } finally {
            finishQueueActivity();
            runOnMain(() -> player.getPrefs().edit().putString(autoplayKey, original)
                    .putBoolean(commentsKey, showComments).commit());
        }
    }

    @Test
    public void nextAtSingleVideoAndPlaylistTailChainsRecommendations() throws Exception {
        runOnMain(() -> {
            final StreamInfo first = recommendationInfo("A", "B");
            final SinglePlayQueue queue = new SinglePlayQueue(first);
            queue.init();
            setField("playQueue", queue);
            setField("currentMetadata", StreamInfoTag.of(first));
            player.playNext();
        });
        assertTrue("single video did not continue to its recommendation", waitFor(
                () -> callOnMain(() -> player.getPlayQueue().getIndex() == 1),
                PLAYBACK_TIMEOUT_SECONDS));
        runOnMain(() -> {
            assertEquals("B", player.getPlayQueue().getItem().getUrl());
            setField("currentMetadata", StreamInfoTag.of(recommendationInfo("B", "C")));
            player.playNext();
        });
        assertTrue("playlist tail did not continue with another recommendation", waitFor(
                () -> callOnMain(() -> player.getPlayQueue().getIndex() == 2),
                PLAYBACK_TIMEOUT_SECONDS));
        assertEquals("C", callOnMain(() -> player.getPlayQueue().getItem().getUrl()));
    }

    @Test
    public void dataSaverBlocksMuxedFallbackAndGenericLiveManifest() throws Exception {
        runOnMain(() -> {
            player.getPrefs().edit().putBoolean(context.getString(R.string.data_saver_key), true)
                    .commit();
            setField("playerType", org.schabi.newpipe.player.PlayerType.AUDIO);
            final StreamInfo info = new StreamInfo(1, "offline-policy", "offline-policy",
                    StreamType.VIDEO_STREAM, "Offline video only", "", 0);
            info.setAudioStreams(List.of());
            info.setVideoStreams(List.of(new VideoStream.Builder().setId("muxed")
                    .setContent(Uri.fromFile(localAudio).toString(), true)
                    .setMediaFormat(MediaFormat.MPEG_4).setResolution("360p")
                    .setIsVideoOnly(false).build()));
            final PlayQueueItem item = new PlayQueueItem(info);
            final var blocked = player.sourceOf(item, info);
            assertTrue(blocked instanceof FailedMediaSource);
            assertTrue(((FailedMediaSource) blocked).getError()
                    instanceof FailedMediaSource.AudioOnlyUnavailableException);
            setField("playerType", org.schabi.newpipe.player.PlayerType.MAIN);
            setField("isAudioOnly", true);
            assertTrue(player.sourceOf(item, info) instanceof FailedMediaSource);
            final Method shouldReload = org.schabi.newpipe.player.Player.class.getDeclaredMethod(
                    "playQueueManagerReloadingNeeded",
                    org.schabi.newpipe.player.resolver.VideoPlaybackResolver.SourceType.class,
                    StreamInfo.class, int.class);
            shouldReload.setAccessible(true);
            assertEquals(true, shouldReload.invoke(player,
                    org.schabi.newpipe.player.resolver.VideoPlaybackResolver.SourceType
                            .VIDEO_WITH_AUDIO_OR_AUDIO_ONLY, info, 0));
            setField("isAudioOnly", false);
            assertFalse(player.sourceOf(item, info) instanceof FailedMediaSource);
            setField("playerType", org.schabi.newpipe.player.PlayerType.AUDIO);
            final StreamInfo live = new StreamInfo(1, "offline-live", "offline-live",
                    StreamType.LIVE_STREAM, "Offline live", "", 0);
            live.setHlsUrl("https://example.invalid/unused.m3u8");
            assertTrue(player.sourceOf(new PlayQueueItem(live), live) instanceof FailedMediaSource);
            info.setAudioStreams(List.of(new AudioStream.Builder().setId("audio-only")
                    .setContent(Uri.fromFile(localAudio).toString(), true)
                    .setMediaFormat(MediaFormat.WAV).setAverageBitrate(128).build()));
            assertFalse(player.sourceOf(item, info) instanceof FailedMediaSource);
            info.setAudioStreams(List.of());
            player.getPrefs().edit().putBoolean(context.getString(R.string.data_saver_key), false)
                    .commit();
            assertTrue(player.sourceOf(item, info) != null);
            assertFalse(player.sourceOf(item, info) instanceof FailedMediaSource);
            final List<AudioStream> rates = List.of(
                    new AudioStream.Builder().setId("high").setContent("", true)
                            .setMediaFormat(MediaFormat.M4A).setAverageBitrate(320).build(),
                    new AudioStream.Builder().setId("low").setContent("", true)
                            .setMediaFormat(MediaFormat.WEBMA).setAverageBitrate(48).build());
            player.getPrefs().edit().putString(context.getString(R.string.audio_quality_key),
                    DataSaver.LOW).commit();
            assertEquals(48, ListHelper.getFilteredAudioStreams(context, rates).get(0)
                    .getAverageBitrate());
            player.getPrefs().edit().putString(context.getString(R.string.audio_quality_key),
                    DataSaver.HIGH).commit();
            assertEquals(320, ListHelper.getFilteredAudioStreams(context, rates).get(0)
                    .getAverageBitrate());
        });
    }

    @Test
    public void qualityReloadKeepsPausedPositionAndDataSaverUiRenders() throws Exception {
        startOfflineRecommendationChain(false);
        runOnMain(() -> {
            player.pause();
            player.getExoPlayer().seekTo(1200);
        });
        assertTrue(waitFor(() -> callOnMain(
                () -> player.getExoPlayer().getCurrentPosition() >= 1100),
                PLAYBACK_TIMEOUT_SECONDS));
        runOnMain(() -> player.getPrefs().edit()
                .putString(context.getString(R.string.audio_quality_key), DataSaver.LOW).commit());
        assertTrue("quality reload lost paused position", waitFor(() -> callOnMain(
                () -> player.getExoPlayer().getPlaybackState() == Player.STATE_READY
                        && Math.abs(player.getExoPlayer().getCurrentPosition() - 1200) < 150
                        && !player.getPlayWhenReady()), 10));
        InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PlayQueueActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue(waitFor(() -> callOnMain(() -> activeQueueActivity() != null), 5));
        runOnMain(() -> ((androidx.appcompat.widget.Toolbar) activeQueueActivity()
                .findViewById(R.id.toolbar)).showOverflowMenu());
        captureScreen("backtube-data-saver-menu-portrait.png");
        runOnMain(() -> {
            setField("playerType", org.schabi.newpipe.player.PlayerType.MAIN);
            setField("isAudioOnly", false);
        });
        clickAccessibilityText(context.getString(R.string.data_saver_title));
        assertTrue("saver switch resumed paused playback", waitFor(() -> callOnMain(
                () -> player.audioPlayerSelected() && !player.getPlayWhenReady()
                        && player.getExoPlayer().getPlaybackState() == Player.STATE_READY
                        && Math.abs(player.getExoPlayer().getCurrentPosition() - 1200) < 150), 10));
        runOnMain(() -> {
            assertTrue(DataSaver.isEnabled(context));
            assertEquals(DataSaver.LOW, DataSaver.getAudioQuality(context));
            activeQueueActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        });
        assertTrue(waitFor(() -> callOnMain(() -> activeQueueActivity() != null
                && activeQueueActivity().getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT), 5));
        captureScreen("backtube-data-saver-portrait.png");
        runOnMain(() -> ((androidx.appcompat.widget.Toolbar) activeQueueActivity()
                .findViewById(R.id.toolbar)).showOverflowMenu());
        clickAccessibilityText(context.getString(R.string.audio_quality_current,
                context.getString(DataSaver.getQualityLabel(context))));
        captureScreen("backtube-audio-quality-dialog.png");
        clickAccessibilityText(context.getString(R.string.audio_quality_high));
        assertTrue("quality selection did not persist", waitFor(() -> DataSaver.HIGH.equals(
                player.getPrefs().getString(context.getString(R.string.audio_quality_key), "")),
                5));
        assertEquals(DataSaver.HIGH, player.getPrefs().getString(
                context.getString(R.string.audio_quality_key), ""));
        runOnMain(() -> activeQueueActivity().setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        assertTrue(waitFor(() -> callOnMain(() -> activeQueueActivity() != null
                && activeQueueActivity().getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE), 5));
        captureScreen("backtube-data-saver-landscape.png");
    }

    @Test
    public void meteredSavingsOverrideQualityAndImagesWithoutOverwritingPreferences()
            throws Exception {
        final String expected = InstrumentationRegistry.getArguments()
                .getString("expected_metered", "");
        if (!expected.isEmpty()) {
            assertTrue("network policy did not update", waitFor(
                    () -> ListHelper.isMeteredNetwork(context)
                            == Boolean.parseBoolean(expected), 5));
        }
        runOnMain(() -> player.getPrefs().edit()
                .putBoolean(context.getString(R.string.data_saver_key), true)
                .putString(context.getString(R.string.audio_quality_key), DataSaver.HIGH).commit());
        final boolean metered = ListHelper.isMeteredNetwork(context);
        assertEquals(metered, DataSaver.isMeteredSavingsActive(context));
        assertEquals(metered ? DataSaver.LOW : DataSaver.HIGH, DataSaver.getAudioQuality(context));
        assertEquals(DataSaver.HIGH, player.getPrefs().getString(
                context.getString(R.string.audio_quality_key), ""));
        final List<Image> images = List.of(
                new Image("small", 75, 75, Image.ResolutionLevel.LOW),
                new Image("large", 500, 500, Image.ResolutionLevel.HIGH));
        try {
            ImageStrategy.setPreferredImageQuality(PreferredImageQuality.HIGH);
            assertEquals(metered ? "small" : "large", ImageStrategy.choosePreferredImage(images));
            assertEquals("large", ImageStrategy.imageListToDbUrl(images));
            ImageStrategy.setPreferredImageQuality(PreferredImageQuality.NONE);
            assertTrue(ImageStrategy.choosePreferredImage(images) == null);
            player.getPrefs().edit().putBoolean(context.getString(R.string.data_saver_key), false)
                    .commit();
            assertFalse(DataSaver.isMeteredSavingsActive(context));
            assertEquals(DataSaver.HIGH, DataSaver.getAudioQuality(context));
        } finally {
            ImageStrategy.setPreferredImageQuality(PreferredImageQuality.MEDIUM);
        }
    }

    @Test
    public void networkPolicyReloadsCurrentAudioAndKeepsPausedPosition() throws Exception {
        final String wifiId = InstrumentationRegistry.getArguments()
                .getString("metered_wifi_id", "");
        // Shell network policy mutation is opt-in, only on a dedicated test emulator.
        org.junit.Assume.assumeTrue(wifiId.matches("[A-Za-z0-9_. -]+"));
        final String policies = shellCommand("cmd netpolicy list wifi-networks");
        final String original = policies.lines().filter(line -> line.startsWith(wifiId + ";"))
                .findFirst().orElseThrow().substring(wifiId.length() + 1).trim();
        final String restore = "none".equals(original) ? "undefined" : original;
        final String command = "cmd netpolicy set metered-network \"" + wifiId + "\" ";
        final String limitKey = context.getString(R.string.limit_mobile_data_usage_key);
        final String originalLimit = player.getPrefs().getString(limitKey,
                context.getString(R.string.limit_data_usage_none_key));
        try {
            shellCommand(command + "false");
            assertTrue(waitFor(() -> !ListHelper.isMeteredNetwork(context), 5));
            startOfflineRecommendationChain(false);
            runOnMain(() -> {
                final StreamInfo info = (StreamInfo) InfoCache.getInstance()
                        .getFromKey(1, "offline-A", InfoCache.Type.STREAM);
                info.setAudioStreams(List.of(
                        new AudioStream.Builder().setId("high")
                                .setContent(Uri.fromFile(localAudio).toString(), true)
                                .setMediaFormat(MediaFormat.WAV).setAverageBitrate(320).build(),
                        new AudioStream.Builder().setId("low")
                                .setContent(Uri.fromFile(localAudio).toString(), true)
                                .setMediaFormat(MediaFormat.WAV).setAverageBitrate(48).build()));
                player.pause();
                player.getExoPlayer().seekTo(1200);
                player.getPrefs().edit()
                        .putBoolean(context.getString(R.string.data_saver_key), true)
                        .putString(context.getString(R.string.audio_quality_key), DataSaver.HIGH)
                        .commit();
            });
            assertPausedAudioBitrate(320);
            for (int transition = 0; transition < 3; transition++) {
                shellCommand(command + "true");
                assertPausedAudioBitrate(48);
                assertTrue(androidx.core.content.ContextCompat.getSystemService(context,
                        android.net.ConnectivityManager.class).isActiveNetworkMetered());
                assertEquals(DataSaver.HIGH, player.getPrefs().getString(
                        context.getString(R.string.audio_quality_key), ""));
                final Object meteredManager = callOnMain(this::queueManager);
                shellCommand(command + "true");
                SystemClock.sleep(500);
                assertTrue("duplicate network event reloaded the queue",
                        meteredManager == callOnMain(this::queueManager));
                shellCommand(command + "false");
                assertTrue("unmetered network policy did not update", waitFor(
                        () -> !ListHelper.isMeteredNetwork(context), 5));
                assertPausedAudioBitrate(320);
                assertFalse(androidx.core.content.ContextCompat.getSystemService(context,
                        android.net.ConnectivityManager.class).isActiveNetworkMetered());
            }
            runOnMain(() -> {
                // Exercise the API 23 broadcast fallback with a stale callback snapshot.
                ListHelper.setPlayerNetworkMetered(true);
                final Method receive = org.schabi.newpipe.player.Player.class.getDeclaredMethod(
                        "onBroadcastReceived", Intent.class);
                receive.setAccessible(true);
                receive.invoke(player, new Intent(android.net.ConnectivityManager
                        .CONNECTIVITY_ACTION));
                assertFalse(ListHelper.isMeteredNetwork(context));
            });
            runOnMain(() -> player.getPrefs().edit()
                    .putBoolean(context.getString(R.string.data_saver_key), false)
                    .putString(limitKey, "360p").commit());
            assertPausedAudioBitrate(320);
            final Object disabledManager = callOnMain(this::queueManager);
            shellCommand(command + "true");
            assertTrue(waitFor(() -> ListHelper.isMeteredNetwork(context), 5));
            SystemClock.sleep(500);
            assertTrue("disabled saver reloaded the queue",
                    disabledManager == callOnMain(this::queueManager));
            assertPausedAudioBitrate(320);
        } finally {
            shellCommand(command + restore);
            runOnMain(() -> player.getPrefs().edit().putString(limitKey, originalLimit).commit());
        }
    }

    private void assertPausedAudioBitrate(final int bitrate) throws Exception {
        assertTrue("network reload lost quality, paused state, or position: " + bitrate,
                waitFor(() -> callOnMain(() -> player.getSelectedAudioStream()
                        .map(stream -> stream.getAverageBitrate() == bitrate).orElse(false)
                        && player.getExoPlayer().getPlaybackState() == Player.STATE_READY
                        && !player.getPlayWhenReady()
                        && Math.abs(player.getExoPlayer().getCurrentPosition() - 1200) < 150), 10));
    }

    private Object queueManager() throws Exception {
        final Field field = org.schabi.newpipe.player.Player.class
                .getDeclaredField("playQueueManager");
        field.setAccessible(true);
        return field.get(player);
    }

    private String shellCommand(final String command) throws IOException {
        try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry
                .getInstrumentation().getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    public void naturalEndAppendsRecommendationWhenAutoQueueEnabled() throws Exception {
        runOnMain(() -> {
            final StreamInfo info = recommendationInfo("A", "B");
            final SinglePlayQueue queue = new SinglePlayQueue(info);
            queue.init();
            setField("playQueue", queue);
            player.setAutoQueueEnabled(true);
        });
        prepareAndPlayLocalAudio();
        runOnMain(() -> {
            setField("currentMetadata", StreamInfoTag.of(recommendationInfo("A", "B")));
            player.getExoPlayer().seekTo(3800);
        });
        assertTrue("natural end did not request a recommendation", waitFor(
                () -> callOnMain(() -> player.getPlayQueue().getIndex() == 1),
                PLAYBACK_TIMEOUT_SECONDS));
        assertEquals("B", callOnMain(() -> player.getPlayQueue().getItem().getUrl()));
    }

    @Test
    public void pendingRecommendationIsCancelledByPauseAndDuplicateNextIsCoalesced()
            throws Exception {
        final SingleSubject<StreamInfo> result = SingleSubject.create();
        runOnMain(() -> {
            final PlayQueueItem item = new PlayQueueItem(recommendationInfo("A", "B")) {
                @Override
                public Single<StreamInfo> getStream() {
                    return result;
                }
            };
            final SinglePlayQueue queue = new SinglePlayQueue(item);
            queue.init();
            setField("playQueue", queue);
            player.playNext();
            player.playNext();
            player.pause();
            result.onSuccess(recommendationInfo("A", "B"));
        });
        Thread.sleep(250);
        assertEquals(1, (int) callOnMain(() -> player.getPlayQueue().size()));
        assertFalse(callOnMain(player::getPlayWhenReady));
    }

    private static StreamInfo recommendationInfo(final String url, final String next) {
        final StreamInfo info = new StreamInfo(0, url, url, StreamType.AUDIO_STREAM, url, "", 0);
        info.setRelatedItems(List.of(new StreamInfoItem(0, next, next, StreamType.AUDIO_STREAM)));
        return info;
    }

    @Test
    public void videoExclusionPersistsUntilUndoAndDoesNotTouchManualNext() throws Exception {
        final StreamInfo info = recommendationInfo("A", "B");
        info.setRelatedItems(List.of(
                recommendationItem("B", "channel-b"),
                recommendationItem("C", "channel-c")));
        setRecommendationQueue(info);
        awaitRecommendation("B");

        final PlayQueueItem excluded = callOnMain(player::getNextRecommendation);
        assertTrue("video exclusion was rejected", callOnMain(
                () -> player.excludeNextRecommendation(false)));
        awaitRecommendation("C");
        assertTrue(callOnMain(player::hasRecommendationExclusions));
        assertTrue("exclusion was not retained by a new helper",
                new RecommendationExclusions(PreferenceManager.getDefaultSharedPreferences(context))
                        .excludes(0, "B", "channel-b"));

        runOnMain(() -> player.undoRecommendationExclusion(excluded, false));
        awaitRecommendation("B");
        assertFalse("undo left the video excluded",
                new RecommendationExclusions(PreferenceManager.getDefaultSharedPreferences(context))
                        .excludes(0, "B", "channel-b"));
        setRecommendationQueue(info);
        awaitRecommendation("B");

        runOnMain(() -> {
            final SinglePlayQueue manualQueue = new SinglePlayQueue(List.of(
                    new StreamInfoItem(0, "manual-A", "A", StreamType.AUDIO_STREAM),
                    new StreamInfoItem(0, "manual-B", "B", StreamType.AUDIO_STREAM)), 0);
            manualQueue.init();
            setField("playQueue", manualQueue);
            setField("currentMetadata", StreamInfoTag.of(recommendationInfo("manual-A", "C")));
            assertFalse("manual next was treated as an automatic recommendation",
                    player.excludeNextRecommendation(false));
            assertEquals(2, manualQueue.size());
            assertEquals("manual-B", manualQueue.getItem(1).getUrl());
        });
    }

    @Test
    public void channelExclusionSkipsSameUploaderAndMissingUploaderIsIgnored() throws Exception {
        final StreamInfo info = recommendationInfo("A", "missing");
        info.setRelatedItems(List.of(
                recommendationItem("missing", ""),
                recommendationItem("B", "channel-a"),
                recommendationItem("C", "channel-a"),
                recommendationItem("D", "channel-d")));
        setRecommendationQueue(info);
        awaitRecommendation("missing");
        assertFalse("missing uploader created a channel exclusion", callOnMain(
                () -> player.excludeNextRecommendation(true)));
        assertFalse(callOnMain(player::hasRecommendationExclusions));

        assertTrue(callOnMain(() -> player.excludeNextRecommendation(false)));
        awaitRecommendation("B");
        assertTrue(callOnMain(() -> player.excludeNextRecommendation(true)));
        awaitRecommendation("D");
    }

    @Test
    public void savedLocalAudioSessionRestoresPausedThroughServiceAction() throws Exception {
        startOfflineRecommendationChain(false);
        runOnMain(() -> {
            final StreamInfo first = (StreamInfo) InfoCache.getInstance()
                    .getFromKey(1, "offline-A", InfoCache.Type.STREAM);
            final StreamInfo second = (StreamInfo) InfoCache.getInstance()
                    .getFromKey(1, "offline-B", InfoCache.Type.STREAM);
            final SinglePlayQueue queue = new SinglePlayQueue(first);
            queue.append(new SinglePlayQueue(second).getStreams());
            queue.setIndex(1);
            queue.setRecovery(1, 1200);
            final Method init = org.schabi.newpipe.player.Player.class.getDeclaredMethod(
                    "initPlayback", org.schabi.newpipe.player.playqueue.PlayQueue.class,
                    boolean.class);
            init.setAccessible(true);
            init.invoke(player, queue, false);
            player.getExoPlayer().setRepeatMode(Player.REPEAT_MODE_ONE);
            player.setSleepTimer(60_000);
            player.saveLastSession();
        });

        final LastPlaybackSessionStore.Snapshot saved = new LastPlaybackSessionStore(
                PreferenceManager.getDefaultSharedPreferences(context)).load();
        assertTrue("session was not saved", saved != null);
        assertEquals(2, saved.getQueue().size());
        assertEquals(1, saved.getQueue().getIndex());
        assertEquals("offline-A", saved.getQueue().getItem(0).getUrl());
        assertEquals("offline-B", saved.getQueue().getItem(1).getUrl());
        assertEquals(1200, saved.getQueue().getItem(1).getRecoveryPosition());
        assertEquals(Player.REPEAT_MODE_ONE, saved.getRepeatMode());

        runOnMain(service::destroyPlayerAndStopService);
        final PlayQueueActivity queueActivity = (PlayQueueActivity) InstrumentationRegistry
                .getInstrumentation().startActivitySync(new Intent(context, PlayQueueActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue("saved session was not restored from the queue screen", waitFor(
                () -> callOnMain(() -> activeQueueActivity() == queueActivity
                        && service != null && service.getPlayer() != null
                        && service.getPlayer().getPlayQueue() != null
                        && service.getPlayer().getPlayQueue().getIndex() == 1),
                SERVICE_TIMEOUT_SECONDS));
        player = callOnMain(service::getPlayer);
        assertTrue(player.audioPlayerSelected());
        assertFalse("restored session started playing", callOnMain(player::getPlayWhenReady));
        assertEquals(Player.REPEAT_MODE_ONE, (int) callOnMain(player::getRepeatMode));
        assertEquals(0, (long) callOnMain(player::getSleepTimerRemainingMillis));
        assertTrue("restored position was lost", waitFor(
                () -> callOnMain(() -> player.getExoPlayer().getCurrentPosition() >= 1100),
                PLAYBACK_TIMEOUT_SECONDS));

        runOnMain(player::play);
        assertTrue("explicit play did not start restored local audio", waitFor(
                () -> callOnMain(player::isPlaying), PLAYBACK_TIMEOUT_SECONDS));

        final org.schabi.newpipe.player.Player activePlayer = player;
        final org.schabi.newpipe.player.playqueue.PlayQueue activeQueue =
                callOnMain(player::getPlayQueue);
        androidx.core.content.ContextCompat.startForegroundService(context,
                new Intent(context, PlayerService.class)
                .setAction(PlayerService.ACTION_RESTORE_LAST_SESSION)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        assertTrue("restore action replaced an active queue", waitFor(
                () -> callOnMain(() -> service.getPlayer() == activePlayer
                        && service.getPlayer().getPlayQueue() == activeQueue),
                PLAYBACK_TIMEOUT_SECONDS));
    }

    @Test
    public void emptySavedSessionFinishesQueueScreenWithoutPlayer() throws Exception {
        new LastPlaybackSessionStore(
                PreferenceManager.getDefaultSharedPreferences(context)).clear();
        runOnMain(service::destroyPlayerAndStopService);

        final PlayQueueActivity queueActivity = (PlayQueueActivity) InstrumentationRegistry
                .getInstrumentation().startActivitySync(new Intent(context, PlayQueueActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue("queue screen stayed open without a saved session", waitFor(
                () -> callOnMain(queueActivity::isFinishing), SERVICE_TIMEOUT_SECONDS));
        assertTrue("empty session left a player running", waitFor(
                () -> callOnMain(() -> service.getPlayer() == null), SERVICE_TIMEOUT_SECONDS));
    }

    private static StreamInfoItem recommendationItem(final String url, final String uploaderUrl) {
        final StreamInfoItem item = new StreamInfoItem(0, url, url, StreamType.AUDIO_STREAM);
        item.setUploaderUrl(uploaderUrl);
        return item;
    }

    private void setRecommendationQueue(final StreamInfo info) throws Exception {
        runOnMain(() -> {
            final SinglePlayQueue queue = new SinglePlayQueue(info);
            queue.init();
            setField("playQueue", queue);
            setField("currentMetadata", StreamInfoTag.of(info));
            player.loadNextRecommendation();
        });
    }

    private void awaitRecommendation(final String url) throws Exception {
        assertTrue("recommendation was not ready: " + url, waitFor(() -> callOnMain(() ->
                player.getRecommendationStatus()
                        == org.schabi.newpipe.player.Player.RecommendationStatus.READY
                        && player.getNextRecommendation() != null
                        && url.equals(player.getNextRecommendation().getUrl())),
                PLAYBACK_TIMEOUT_SECONDS));
    }

    @Test
    public void recommendedAudioActuallyPlaysThroughTheMediaSourceManager() throws Exception {
        startOfflineRecommendationChain(false);
        runOnMain(player::playNext);
        assertTrue("recommended B was not playing", waitFor(
                () -> callOnMain(() -> player.isPlaying()
                        && "offline-B".equals(player.getVideoUrl())), 10));
        runOnMain(player::playNext);
        assertTrue("recommended C was not playing", waitFor(
                () -> callOnMain(() -> player.isPlaying()
                        && "offline-C".equals(player.getVideoUrl())), 10));
    }

    @Test
    public void relatedListStartsWithActualNextAndHidesUsedSongs() throws Exception {
        final String commentsKey = context.getString(R.string.show_comments_key);
        final boolean showComments = player.getPrefs().getBoolean(commentsKey, true);
        try {
            startOfflineRecommendationChain(false);
            runOnMain(() -> {
                player.pause();
                final StreamInfo info = (StreamInfo) InfoCache.getInstance()
                        .getFromKey(1, "offline-A", InfoCache.Type.STREAM);
                info.setRelatedItems(List.of(
                        new StreamInfoItem(1, "offline-A", "offline-A", StreamType.AUDIO_STREAM),
                        new StreamInfoItem(1, "offline-B", "offline-B", StreamType.AUDIO_STREAM),
                        new StreamInfoItem(1, "offline-C", "offline-C", StreamType.AUDIO_STREAM)));
                player.setAutoQueueEnabled(true);
                player.getPrefs().edit().putBoolean(commentsKey, false).commit();
            });
            final MainActivity launchedActivity = (MainActivity) InstrumentationRegistry
                    .getInstrumentation().startActivitySync(new Intent(context, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMain(() -> launchedActivity.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            assertTrue(waitFor(() -> callOnMain(() -> activeMainActivity() != null
                    && activeMainActivity().getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_PORTRAIT), 5));
            final MainActivity activity = callOnMain(this::activeMainActivity);
            runOnMain(() -> NavigationHelper.openVideoDetailFragment(activity,
                    activity.getSupportFragmentManager(), 1, "offline-A", "offline-A",
                    player.getPlayQueue(), true));
            assertTrue("related list did not load", waitFor(
                    () -> callOnMain(() -> !relatedListUrls(activity).isEmpty()), 5));
            assertEquals("the first visible song differs from actual next",
                    callOnMain(() -> player.getNextRecommendation().getUrl()),
                    callOnMain(() -> relatedListUrls(activity).get(0)));
            assertFalse(callOnMain(() -> relatedListUrls(activity).contains("offline-A")));
            captureScreen("backtube-next-list-aligned.png");
            runOnMain(player::replaceNextRecommendation);
            assertTrue("replacement did not reach the first visible position", waitFor(
                    () -> callOnMain(() -> !relatedListUrls(activity).isEmpty()
                            && "offline-C".equals(relatedListUrls(activity).get(0))), 5));
            assertEquals("offline-C", callOnMain(() -> player.getNextRecommendation().getUrl()));
            runOnMain(() -> activity.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            final boolean rotationAligned = waitFor(
                    () -> callOnMain(() -> activeMainActivity() != null
                            && activeMainActivity().getResources().getConfiguration().orientation
                            == Configuration.ORIENTATION_LANDSCAPE
                            && !relatedListUrls(activeMainActivity()).isEmpty()
                            && "offline-C".equals(relatedListUrls(activeMainActivity()).get(0))),
                    5);
            assertTrue("rotation state: " + callOnMain(() -> activeMainActivity() == null
                    ? "no resumed activity" : activeMainActivity().getResources()
                    .getConfiguration().orientation + " / " + relatedListUrls(activeMainActivity())
                    + " / next=" + player.getNextRecommendation()), rotationAligned);
            runOnMain(() -> ((com.google.android.material.appbar.AppBarLayout) activeMainActivity()
                    .findViewById(R.id.app_bar_layout)).setExpanded(false, false));
            captureScreen("backtube-next-list-landscape.png");
            runOnMain(() -> assertTrue(player.excludeNextRecommendation(false)));
            assertTrue("explicitly excluded song remained in the list", waitFor(
                    () -> callOnMain(() -> relatedListUrls(activeMainActivity()).isEmpty()), 5));
            assertNull(callOnMain(player::getNextRecommendation));
        } finally {
            finishQueueActivity();
            runOnMain(() -> player.getPrefs().edit()
                    .putBoolean(commentsKey, showComments).commit());
        }
    }

    @Test
    public void removedSongCanReturnUnlessExplicitlyExcluded() throws Exception {
        startOfflineRecommendationChain(false);
        runOnMain(() -> {
            final StreamInfo info = (StreamInfo) InfoCache.getInstance()
                    .getFromKey(1, "offline-B", InfoCache.Type.STREAM);
            info.setRelatedItems(List.of(
                    new StreamInfoItem(1, "offline-A", "offline-A", StreamType.AUDIO_STREAM),
                    new StreamInfoItem(1, "offline-C", "offline-C", StreamType.AUDIO_STREAM)));
            player.getPlayQueue().append(new SinglePlayQueue(info).getStreams());
            player.playNext();
        });
        assertTrue("B did not start", waitFor(() -> callOnMain(() -> player.isPlaying()
                && "offline-B".equals(player.getVideoUrl())), 5));
        runOnMain(() -> {
            player.pause();
            player.getPlayQueue().remove(0);
            player.setAutoQueueEnabled(true);
            assertEquals("deletion permanently blocked a song",
                    "offline-A", player.getNextRecommendation().getUrl());
            assertTrue(player.excludeNextRecommendation(false));
        });
        awaitRecommendation("offline-C");
        assertTrue(new RecommendationExclusions(player.getPrefs()).excludes(1, "offline-A", null));
    }

    private MainActivity activeMainActivity() {
        for (final android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (activity instanceof MainActivity) {
                return (MainActivity) activity;
            }
        }
        return null;
    }

    private List<String> relatedListUrls(final MainActivity activity) throws Exception {
        final VideoDetailFragment detail = (VideoDetailFragment) activity
                .getSupportFragmentManager().findFragmentById(R.id.fragment_player_holder);
        if (detail == null) {
            return List.of();
        }
        for (final androidx.fragment.app.Fragment fragment
                : detail.getChildFragmentManager().getFragments()) {
            if (fragment instanceof RelatedItemsFragment) {
                final Field field = BaseListFragment.class.getDeclaredField("infoListAdapter");
                field.setAccessible(true);
                final InfoListAdapter adapter = (InfoListAdapter) field.get(fragment);
                return adapter == null ? List.of() : adapter.getItemsList().stream()
                        .map(org.schabi.newpipe.extractor.InfoItem::getUrl).toList();
            }
        }
        return List.of();
    }

    @Test
    public void naturalEndPlaysQueuedItemsAndStopsAtTailWhenAutoQueueDisabled()
            throws Exception {
        startOfflineRecommendationChain(false);
        runOnMain(() -> {
            final StreamInfo next = (StreamInfo) InfoCache.getInstance()
                    .getFromKey(1, "offline-B", InfoCache.Type.STREAM);
            player.getPlayQueue().append(new SinglePlayQueue(next).getStreams());
        });
        assertTrue("natural end did not play the queued B", waitFor(
                () -> callOnMain(() -> player.isPlaying()
                        && "offline-B".equals(player.getVideoUrl())), 10));
        assertTrue("disabled auto queue did not stop at the tail", waitFor(
                () -> callOnMain(() -> player.getExoPlayer().getPlaybackState()
                        == Player.STATE_ENDED), 10));
        assertEquals("offline-B", callOnMain(player::getVideoUrl));
        assertEquals(1, (int) callOnMain(() -> player.getPlayQueue().getIndex()));
        assertEquals(2, (int) callOnMain(() -> player.getPlayQueue().size()));
        assertFalse(callOnMain(player::isPlaying));
    }

    @Test
    public void naturalEndActuallyPlaysRecommendedAudioThroughTheMediaSourceManager()
            throws Exception {
        startOfflineRecommendationChain(true);
        runOnMain(() -> {
            for (final String id : List.of("offline-B", "offline-C")) {
                final StreamInfo info = (StreamInfo) InfoCache.getInstance()
                        .getFromKey(1, id, InfoCache.Type.STREAM);
                info.setRelatedItems(List.of(
                        new StreamInfoItem(1, "offline-A", "offline-A", StreamType.AUDIO_STREAM),
                        new StreamInfoItem(1, "offline-B", "offline-B", StreamType.AUDIO_STREAM),
                        new StreamInfoItem(1, "offline-C", "offline-C", StreamType.AUDIO_STREAM)));
            }
        });
        assertTrue("natural end did not play recommended B", waitFor(
                () -> callOnMain(() -> player.isPlaying()
                        && "offline-B".equals(player.getVideoUrl())), 10));
        assertTrue("the second natural end did not play recommended C", waitFor(
                () -> callOnMain(() -> player.isPlaying()
                        && "offline-C".equals(player.getVideoUrl())), 10));
        assertTrue("exhausted recommendations looped instead of finishing", waitFor(
                () -> callOnMain(() -> player.getExoPlayer().getPlaybackState()
                        == Player.STATE_ENDED), 10));
        assertEquals("offline-C", callOnMain(player::getVideoUrl));
        assertEquals(2, (int) callOnMain(() -> player.getPlayQueue().getIndex()));
        assertFalse(callOnMain(player::isPlaying));
    }

    private void startOfflineRecommendationChain(final boolean automatic) throws Exception {
        runOnMain(() -> {
            cacheOfflineRecommendationChain();
            final StreamInfo first = (StreamInfo) InfoCache.getInstance()
                    .getFromKey(1, "offline-A", InfoCache.Type.STREAM);
            player.setAutoQueueEnabled(automatic);
            setField("playerType", org.schabi.newpipe.player.PlayerType.AUDIO);
            final Method init = org.schabi.newpipe.player.Player.class.getDeclaredMethod(
                    "initPlayback", org.schabi.newpipe.player.playqueue.PlayQueue.class,
                    boolean.class);
            init.setAccessible(true);
            init.invoke(player, new SinglePlayQueue(first), true);
        });
        assertTrue("offline chain A did not start", waitFor(
                () -> callOnMain(() -> player.isPlaying()
                        && "offline-A".equals(player.getVideoUrl())), 10));
    }

    private void cacheOfflineRecommendationChain() {
        for (final String id : List.of("offline-A", "offline-B", "offline-C")) {
            final StreamInfo info = new StreamInfo(1, id, id,
                    StreamType.AUDIO_STREAM, id, id, 0);
            info.setDuration(4);
            info.setUploaderName("Offline test");
            info.setAudioStreams(List.of(new AudioStream.Builder().setId(id)
                    .setContent(Uri.fromFile(localAudio).toString(), true)
                    .setMediaFormat(MediaFormat.WAV).setAverageBitrate(128).build()));
            final String next = id.equals("offline-A") ? "offline-B" : "offline-C";
            info.setRelatedItems(id.equals("offline-C") ? List.of() : List.of(
                    new StreamInfoItem(1, next, next, StreamType.AUDIO_STREAM)));
            InfoCache.getInstance().putInfo(1, id, info, InfoCache.Type.STREAM);
        }
    }

    @Test
    public void sessionAcrossProcessRestart() throws Exception {
        org.junit.Assume.assumeTrue(!restartPhase.isEmpty());
        if ("seed".equals(restartPhase)) {
            startOfflineRecommendationChain(false);
            runOnMain(player::playNext);
            assertTrue(waitFor(() -> callOnMain(() -> player.isPlaying()
                    && "offline-B".equals(player.getVideoUrl())), 10));
            runOnMain(() -> {
                player.pause();
                player.getExoPlayer().seekTo(1200);
                player.getExoPlayer().setRepeatMode(Player.REPEAT_MODE_ONE);
                player.setSleepTimer(60_000);
                player.saveLastSession();
                new RecommendationExclusions(player.getPrefs()).excludeVideo(
                        new SinglePlayQueue(new StreamInfoItem(1, "offline-C", "C",
                                StreamType.AUDIO_STREAM)).getItem());
            });
            return;
        }
        final LastPlaybackSessionStore.Snapshot saved = new LastPlaybackSessionStore(
                PreferenceManager.getDefaultSharedPreferences(context)).load();
        assertTrue("process restart lost session", saved != null);
        assertEquals(2, saved.getQueue().size());
        assertEquals(1, saved.getQueue().getIndex());
        assertEquals(1200, saved.getQueue().getItem().getRecoveryPosition());
        assertTrue("process restart lost exclusions", new RecommendationExclusions(
                PreferenceManager.getDefaultSharedPreferences(context))
                .excludes(1, "offline-C", null));
        runOnMain(this::cacheOfflineRecommendationChain);
        InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PlayQueueActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue(waitFor(() -> callOnMain(() -> service.getPlayer().getPlayQueue() != null
                && service.getPlayer().getPlayQueue().getIndex() == 1), 10));
        player = callOnMain(service::getPlayer);
        assertTrue(waitFor(() -> callOnMain(() ->
                player.getExoPlayer().getCurrentPosition() >= 1100), 5));
        assertFalse(callOnMain(player::getPlayWhenReady));
        assertEquals(Player.REPEAT_MODE_ONE, (int) callOnMain(player::getRepeatMode));
        assertEquals(0, (long) callOnMain(player::getSleepTimerRemainingMillis));
        assertTrue("paused restoration did not display its saved position", waitFor(() ->
                callOnMain(() -> activeQueueActivity() != null
                        && "00:01".contentEquals(((android.widget.TextView) activeQueueActivity()
                        .findViewById(R.id.current_time)).getText())), 5));
        captureScreen("personal-session-restored.png");
        runOnMain(player::play);
        assertTrue(waitFor(() -> callOnMain(player::isPlaying), 5));
    }

    @Test
    public void recommendationMenuSupportsExcludeUndoAndClear() throws Exception {
        final StreamInfo info = recommendationInfo("A", "B");
        info.setRelatedItems(List.of(recommendationItem("B", "channel-b"),
                recommendationItem("C", "channel-c")));
        setRecommendationQueue(info);
        awaitRecommendation("B");
        InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PlayQueueActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue(waitFor(() -> callOnMain(() -> activeQueueActivity() != null
                && activeQueueActivity().findViewById(R.id.control_recommendation_options)
                .isEnabled()), 5));
        runOnMain(() -> activeQueueActivity().findViewById(R.id.control_recommendation_options)
                .performClick());
        assertTrue(waitFor(() -> findNodeByText(
                context.getString(R.string.personal_exclude_channel)) != null, 5));
        captureScreen("personal-recommendation-menu.png");
        clickAccessibilityText(context.getString(R.string.personal_exclude_video));
        awaitRecommendation("C");
        clickAccessibilityText(context.getString(R.string.personal_exclusion_undo));
        awaitRecommendation("B");
        runOnMain(() -> {
            assertTrue(player.excludeNextRecommendation(true));
            player.clearRecommendationExclusions();
        });
        awaitRecommendation("B");
        assertFalse(player.hasRecommendationExclusions());
    }

    @Test
    public void endTimerStopsTheRealRecommendationChain() throws Exception {
        startOfflineRecommendationChain(true);
        runOnMain(() -> {
            player.setSleepTimerAtEndOfItem();
            player.getExoPlayer().seekTo(3800);
        });
        assertTrue("end timer did not pause", waitFor(
                () -> !callOnMain(player::getPlayWhenReady), 5));
        Thread.sleep(400);
        assertEquals("offline-A", callOnMain(player::getVideoUrl));
        assertEquals(0, (int) callOnMain(() -> player.getPlayQueue().getIndex()));
    }

    @Test
    public void naturalEndTakesPrecedenceOverPendingPreview() throws Exception {
        final SingleSubject<StreamInfo> result = SingleSubject.create();
        runOnMain(() -> {
            final PlayQueueItem item = new PlayQueueItem(recommendationInfo("A", "B")) {
                @Override
                public Single<StreamInfo> getStream() {
                    return result;
                }
            };
            final SinglePlayQueue queue = new SinglePlayQueue(item);
            queue.init();
            setField("playQueue", queue);
            player.setAutoQueueEnabled(true);
        });
        prepareAndPlayLocalAudio();
        runOnMain(() -> {
            player.loadNextRecommendation();
            player.getExoPlayer().seekTo(3800);
        });
        assertTrue("local audio did not end", waitFor(() -> callOnMain(
                () -> player.getExoPlayer().getPlaybackState() == Player.STATE_ENDED), 5));
        runOnMain(() -> result.onSuccess(recommendationInfo("A", "B")));
        assertTrue("pending preview blocked automatic next", waitFor(() -> callOnMain(
                () -> player.getPlayQueue().getIndex() == 1), 5));
    }

    @Test
    public void nextFetchesRemainingPlaylistBeforeRecommendations() throws Exception {
        runOnMain(() -> {
            final PagedTestQueue queue = new PagedTestQueue(
                    new PlayQueueItem(recommendationInfo("A", "recommendation")),
                    new PlayQueueItem(recommendationInfo("playlist-B", "C")));
            queue.init();
            setField("playQueue", queue);
            setField("currentMetadata", StreamInfoTag.of(
                    recommendationInfo("A", "recommendation")));
            player.setAutoQueueEnabled(true);
            assertEquals(1, queue.size());
            player.playNext();
        });
        assertTrue("next did not advance into the final playlist page", waitFor(
                () -> callOnMain(() -> player.getPlayQueue().getIndex() == 1),
                PLAYBACK_TIMEOUT_SECONDS));
        assertEquals("playlist-B", callOnMain(() -> player.getPlayQueue().getItem().getUrl()));
        assertEquals(2, (int) callOnMain(() -> player.getPlayQueue().size()));
    }

    @Test
    public void previewDoesNotPlayAndReplacementPreservesManualQueue() throws Exception {
        runOnMain(() -> {
            final StreamInfo info = recommendationInfo("A", "B");
            info.setRelatedItems(List.of(
                    new StreamInfoItem(0, "B", "B", StreamType.AUDIO_STREAM),
                    new StreamInfoItem(0, "C", "C", StreamType.AUDIO_STREAM)));
            final SinglePlayQueue queue = new SinglePlayQueue(info);
            queue.init();
            setField("playQueue", queue);
            setField("currentMetadata", StreamInfoTag.of(info));
            player.loadNextRecommendation();
        });
        assertTrue("preview not ready", waitFor(() -> callOnMain(() ->
                player.getRecommendationStatus()
                        == org.schabi.newpipe.player.Player.RecommendationStatus.READY), 5));
        assertEquals("B", callOnMain(() -> player.getNextRecommendation().getUrl()));
        assertEquals(1, (int) callOnMain(() -> player.getPlayQueue().size()));
        assertFalse(callOnMain(player::getPlayWhenReady));
        runOnMain(player::replaceNextRecommendation);
        assertTrue("replacement not ready", waitFor(() -> callOnMain(() ->
                player.getNextRecommendation() != null
                        && "C".equals(player.getNextRecommendation().getUrl())), 5));
        runOnMain(() -> {
            player.getPlayQueue().append(new SinglePlayQueue(
                    new StreamInfoItem(0, "manual", "Manual", StreamType.AUDIO_STREAM))
                    .getStreams());
            assertFalse(player.canReplaceNextRecommendation());
            player.replaceNextRecommendation();
        });
        assertEquals("manual", callOnMain(() -> player.getNextRecommendation().getUrl()));
    }

    @Test
    public void endOfItemTimerStopsBeforeTheNextAudioEvenWithRepeat() throws Exception {
        runOnMain(() -> setField("playQueue", twoItemQueue()));
        prepareAndPlayTwoLocalAudioItems();
        runOnMain(() -> {
            player.cycleNextRepeatMode();
            player.setSleepTimerAtEndOfItem();
            assertTrue(player.isSleepTimerAtEndOfItem());
            player.getExoPlayer().seekTo(0, 3800);
        });
        assertTrue("end-of-item timer did not stop repeat playback", waitFor(
                () -> !callOnMain(player::getPlayWhenReady), 5));
        assertFalse(callOnMain(player::isSleepTimerAtEndOfItem));
        runOnMain(() -> player.getExoPlayer().play());
        Thread.sleep(200);
        assertFalse("timer allowed unexpected resume", callOnMain(player::getPlayWhenReady));
    }

    @Test
    public void timerFadeAndCancellationRespectMute() throws Exception {
        prepareAndPlayLocalAudio();
        runOnMain(() -> {
            player.setSleepTimerFadeEnabled(true);
            player.setSleepTimer(8000);
        });
        Thread.sleep(300);
        assertTrue("fade did not lower player gain", callOnMain(
                () -> player.getExoPlayer().getVolume() < 1));
        runOnMain(() -> {
            player.toggleMute();
            player.cancelSleepTimer();
            assertEquals(0, player.getExoPlayer().getVolume(), 0.001);
            player.toggleMute();
            assertEquals(1, player.getExoPlayer().getVolume(), 0.001);
            player.setSleepTimer(60_000);
            player.extendSleepTimer();
            assertTrue(player.getSleepTimerRemainingMillis() > 15 * 60_000);
            player.setSleepTimerFadeEnabled(false);
        });
    }

    @Test
    public void audioFocusCannotOverrideMuteOrTimerFade() throws Exception {
        prepareAndPlayLocalAudio();
        runOnMain(() -> {
            player.setSleepTimerFadeEnabled(true);
            player.setSleepTimer(8000);
            player.getAudioReactor().onAudioFocusChange(
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
            assertEquals(0.16f, player.getExoPlayer().getVolume(), 0.003f);
            player.toggleMute();
            player.getAudioReactor().onAudioFocusChange(
                    android.media.AudioManager.AUDIOFOCUS_GAIN);
        });
        Thread.sleep(350);
        assertEquals(0, callOnMain(() -> player.getExoPlayer().getVolume()), 0.001);
        runOnMain(() -> {
            player.cancelSleepTimer();
            assertEquals(0, player.getExoPlayer().getVolume(), 0.001);
        });
    }

    @Test
    public void failedPreviewCanRetryAndNextTakesPrecedenceOverLoading() throws Exception {
        final SingleSubject<StreamInfo> result = SingleSubject.create();
        final int[] attempts = {0};
        runOnMain(() -> {
            final PlayQueueItem item = new PlayQueueItem(recommendationInfo("A", "B")) {
                @Override
                public Single<StreamInfo> getStream() {
                    return ++attempts[0] == 1 ? Single.error(new IOException("offline")) : result;
                }
            };
            final SinglePlayQueue queue = new SinglePlayQueue(item);
            queue.init();
            setField("playQueue", queue);
            player.loadNextRecommendation();
        });
        assertTrue("preview error was not exposed", waitFor(() -> callOnMain(
                () -> player.getRecommendationStatus()
                        == org.schabi.newpipe.player.Player.RecommendationStatus.ERROR), 5));
        runOnMain(() -> {
            player.loadNextRecommendation();
            assertEquals(org.schabi.newpipe.player.Player.RecommendationStatus.LOADING,
                    player.getRecommendationStatus());
            player.playNext();
            result.onSuccess(recommendationInfo("A", "B"));
        });
        assertTrue("next was blocked by a loading preview", waitFor(() -> callOnMain(
                () -> player.getPlayQueue().getIndex() == 1), 5));
    }

    @Test
    public void changingRepeatModeCancelsPendingRecommendation() throws Exception {
        final SingleSubject<StreamInfo> result = SingleSubject.create();
        runOnMain(() -> {
            final PlayQueueItem item = new PlayQueueItem(recommendationInfo("A", "B")) {
                @Override
                public Single<StreamInfo> getStream() {
                    return result;
                }
            };
            final SinglePlayQueue queue = new SinglePlayQueue(item);
            queue.init();
            setField("playQueue", queue);
            player.playNext();
            player.cycleNextRepeatMode();
            player.cycleNextRepeatMode();
            result.onSuccess(recommendationInfo("A", "B"));
        });
        Thread.sleep(250);
        assertEquals(1, (int) callOnMain(() -> player.getPlayQueue().size()));
        assertEquals(0, (int) callOnMain(() -> player.getPlayQueue().getIndex()));
    }

    @Test
    public void delayedDuplicateNextAdvancesOnceAndMissingRecommendationsDoNotWrap()
            throws Exception {
        final SingleSubject<StreamInfo> result = SingleSubject.create();
        runOnMain(() -> {
            final PlayQueueItem item = new PlayQueueItem(recommendationInfo("A", "B")) {
                @Override
                public Single<StreamInfo> getStream() {
                    return result;
                }
            };
            final SinglePlayQueue queue = new SinglePlayQueue(item);
            queue.init();
            setField("playQueue", queue);
            player.playNext();
            player.playNext();
            result.onSuccess(recommendationInfo("A", "B"));
        });
        assertTrue("delayed recommendation did not advance", waitFor(
                () -> callOnMain(() -> player.getPlayQueue().getIndex() == 1),
                PLAYBACK_TIMEOUT_SECONDS));
        assertEquals(2, (int) callOnMain(() -> player.getPlayQueue().size()));
        runOnMain(() -> {
            setField("currentMetadata", StreamInfoTag.of(recommendationInfo("B", "A")));
            player.playNext();
        });
        Thread.sleep(250);
        assertEquals(1, (int) callOnMain(() -> player.getPlayQueue().getIndex()));
        assertEquals(2, (int) callOnMain(() -> player.getPlayQueue().size()));
    }

    @Test
    public void sleepTimerPausesAudioAndRejectsAccidentalExoPlayerResume() throws Exception {
        runOnMain(() -> setField("playQueue", twoItemQueue()));
        prepareAndPlayTwoLocalAudioItems();
        runOnMain(() -> player.setSleepTimer(300));

        assertTrue("timer expiry did not pause local audio", waitFor(
                () -> !callOnMain(player::getPlayWhenReady), PLAYBACK_TIMEOUT_SECONDS));
        assertEquals(0, (long) callOnMain(player::getSleepTimerRemainingMillis));

        runOnMain(() -> player.getExoPlayer().play());
        Thread.sleep(250);
        assertFalse("expired timer allowed ExoPlayer to resume without user action",
                callOnMain(player::getPlayWhenReady));

        runOnMain(player::play);
        assertTrue("explicit Player.play did not resume audio", waitFor(
                () -> callOnMain(player::isPlaying), PLAYBACK_TIMEOUT_SECONDS));
    }

    @Test
    public void sleepTimerCanBeCancelledAndRearmedWhileAudioPlays() throws Exception {
        prepareAndPlayLocalAudio();
        runOnMain(() -> player.setSleepTimer(600));
        Thread.sleep(150);
        runOnMain(player::cancelSleepTimer);
        Thread.sleep(700);
        assertTrue("cancelled timer paused audio", callOnMain(player::getPlayWhenReady));
        assertEquals(0, (long) callOnMain(player::getSleepTimerRemainingMillis));

        runOnMain(() -> player.setSleepTimer(300));
        assertTrue("rearmed timer did not pause audio", waitFor(
                () -> !callOnMain(player::getPlayWhenReady), PLAYBACK_TIMEOUT_SECONDS));
    }

    @Test
    public void repeatCycleAndNextNavigationUseTheLivePlayer() throws Exception {
        runOnMain(() -> {
            assertFalse(player.isAutoQueueEnabled());
            player.setAutoQueueEnabled(true);
            assertTrue(player.isAutoQueueEnabled());
            player.setAutoQueueEnabled(false);

            assertEquals(Player.REPEAT_MODE_OFF, player.getRepeatMode());
            player.cycleNextRepeatMode();
            assertEquals(Player.REPEAT_MODE_ONE, player.getRepeatMode());
            player.cycleNextRepeatMode();
            assertEquals(Player.REPEAT_MODE_ALL, player.getRepeatMode());
            player.cycleNextRepeatMode();
            assertEquals(Player.REPEAT_MODE_OFF, player.getRepeatMode());

            final SinglePlayQueue queue = new SinglePlayQueue(List.of(
                    new StreamInfoItem(0, "local-a", "A", StreamType.AUDIO_STREAM),
                    new StreamInfoItem(0, "local-b", "B", StreamType.AUDIO_STREAM)), 0);
            queue.init();
            setField("playQueue", queue);
            player.playNext();
            assertEquals(1, player.getPlayQueue().getIndex());
            setField("currentMetadata", StreamInfoTag.of(recommendationInfo("local-b", "local-a")));
            player.cycleNextRepeatMode();
            player.cycleNextRepeatMode();
            player.playNext();
            assertEquals(0, player.getPlayQueue().getIndex());
        });
    }

    @Test
    public void localAudioAutomaticallyAdvancesToTheNextQueueItem() throws Exception {
        runOnMain(() -> setField("playQueue", twoItemQueue()));
        prepareAndPlayTwoLocalAudioItems();
        runOnMain(() -> player.getExoPlayer().seekTo(0, 3800));

        assertTrue("local audio did not auto-advance", waitFor(
                () -> callOnMain(() -> player.getPlayQueue().getIndex() == 1
                        && player.getExoPlayer().getCurrentMediaItemIndex() == 1),
                PLAYBACK_TIMEOUT_SECONDS));
    }

    @Test
    public void queueOptionsAndTimerDialogRenderInPortraitAndLandscape() throws Exception {
        runOnMain(() -> setField("playQueue", twoItemQueue()));
        prepareAndPlayTwoLocalAudioItems();
        runOnMain(player::pause);
        InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PlayQueueActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue("queue controls did not appear", waitFor(
                () -> callOnMain(() -> activeQueueActivity() != null
                        && activeQueueActivity().findViewById(R.id.control_auto_queue) != null),
                PLAYBACK_TIMEOUT_SECONDS));
        runOnMain(() -> activeQueueActivity().setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        assertTrue("portrait queue controls did not appear", waitFor(
                () -> callOnMain(() -> activeQueueActivity() != null
                        && activeQueueActivity().getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_PORTRAIT), PLAYBACK_TIMEOUT_SECONDS));

        runOnMain(() -> {
            final PlayQueueActivity activity = activeQueueActivity();
            activity.findViewById(R.id.control_repeat).performClick();
            assertEquals(Player.REPEAT_MODE_ONE, player.getRepeatMode());
            assertEquals(context.getString(R.string.personal_repeat_mode,
                            context.getString(R.string.personal_repeat_one)),
                    ((AppCompatButton) activity.findViewById(R.id.control_repeat)).getText());
            activity.findViewById(R.id.control_repeat).performClick();
            activity.findViewById(R.id.control_repeat).performClick();
            final SwitchCompat autoQueue = activity.findViewById(R.id.control_auto_queue);
            assertFalse(autoQueue.isChecked());
            autoQueue.performClick();
            assertTrue(player.isAutoQueueEnabled());
            autoQueue.performClick();
            assertFalse(player.isAutoQueueEnabled());

            final AppCompatButton sleepTimer = activity.findViewById(R.id.control_sleep_timer);
            sleepTimer.performClick();
        });
        assertTrue("sleep timer dialog did not appear", waitFor(
                () -> findNodeByText(context.getString(
                        R.string.personal_sleep_timer_minutes, 15)) != null,
                PLAYBACK_TIMEOUT_SECONDS));
        captureScreen("personal-playback-portrait.png");
        clickAccessibilityText(context.getString(R.string.personal_sleep_timer_minutes, 15));
        assertTrue("sleep timer choice was not applied", waitFor(
                () -> callOnMain(() -> player.getSleepTimerRemainingMillis() > 14 * 60_000L),
                PLAYBACK_TIMEOUT_SECONDS));
        assertEquals(context.getString(R.string.personal_sleep_timer_remaining, 15),
                callOnMain(() -> ((AppCompatButton) activeQueueActivity().findViewById(
                        R.id.control_sleep_timer)).getText().toString()));
        captureScreen("personal-playback-controls-portrait.png");
        assertTrue("elapsed time was hidden when opening a paused player", callOnMain(
                () -> activeQueueActivity().findViewById(R.id.current_time).getWidth() > 0));

        runOnMain(() -> {
            player.pause();
            activeQueueActivity().findViewById(R.id.control_sleep_timer).performClick();
        });
        clickAccessibilityText(context.getString(R.string.personal_sleep_timer_extend));
        assertTrue("15-minute extension was not applied", waitFor(() -> callOnMain(
                () -> player.getSleepTimerRemainingMillis() > 29 * 60_000L), 5));

        runOnMain(() -> activeQueueActivity().findViewById(R.id.control_sleep_timer)
                .performClick());
        clickAccessibilityText(context.getString(R.string.personal_sleep_timer_end_current_item));
        assertTrue("end-of-item timer was not applied",
                waitFor(() -> callOnMain(player::isSleepTimerAtEndOfItem), 5));

        runOnMain(() -> activeQueueActivity().setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        assertTrue("landscape queue controls did not appear", waitFor(
                () -> callOnMain(() -> activeQueueActivity() != null
                        && activeQueueActivity().getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE), PLAYBACK_TIMEOUT_SECONDS));
        captureScreen("personal-playback-landscape.png");
        runOnMain(() -> activeQueueActivity().findViewById(R.id.control_sleep_timer)
                .performClick());
        captureScreen("personal-playback-timer-landscape.png");
        clickAccessibilityText(context.getString(R.string.cancel));
    }

    private void prepareAndPlayLocalAudio() throws Exception {
        runOnMain(() -> {
            final com.google.android.exoplayer2.Player exoPlayer = player.getExoPlayer();
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(localAudio)));
            exoPlayer.prepare();
            exoPlayer.play();
        });
        assertTrue("local WAV did not start", waitFor(
                () -> callOnMain(player::isPlaying), PLAYBACK_TIMEOUT_SECONDS));
    }

    private void prepareAndPlayTwoLocalAudioItems() throws Exception {
        runOnMain(() -> {
            final com.google.android.exoplayer2.Player exoPlayer = player.getExoPlayer();
            final MediaItem audio = MediaItem.fromUri(Uri.fromFile(localAudio));
            exoPlayer.setMediaItems(List.of(audio, audio));
            exoPlayer.prepare();
            exoPlayer.play();
        });
        assertTrue("local WAV queue did not start", waitFor(
                () -> callOnMain(player::isPlaying), PLAYBACK_TIMEOUT_SECONDS));
    }

    private SinglePlayQueue twoItemQueue() {
        final SinglePlayQueue queue = new SinglePlayQueue(List.of(
                new StreamInfoItem(0, "local-a", "A", StreamType.AUDIO_STREAM),
                new StreamInfoItem(0, "local-b", "B", StreamType.AUDIO_STREAM)), 0);
        queue.init();
        return queue;
    }

    private PlayQueueActivity activeQueueActivity() {
        for (final android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (activity instanceof PlayQueueActivity) {
                return (PlayQueueActivity) activity;
            }
        }
        return null;
    }

    private void clickAccessibilityText(final String text) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(300);
        for (int attempt = 0; attempt < 6; attempt++) {
            final AccessibilityNodeInfo visible = findNodeByText(text);
            if (visible != null) {
                visible.recycle();
                break;
            }
            final AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                scrollForward(root);
                root.recycle();
            }
            SystemClock.sleep(300);
        }
        // Wait for native ListView scrolling to settle before reading touch coordinates.
        SystemClock.sleep(800);
        final AccessibilityNodeInfo node = findNodeByText(text);
        assertTrue("missing accessibility text: " + text, node != null);
        final Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        node.recycle();
        assertFalse("empty accessibility bounds: " + text, bounds.isEmpty());
        final long now = SystemClock.uptimeMillis();
        final MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                bounds.centerX(), bounds.centerY(), 0);
        final MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP,
                bounds.centerX(), bounds.centerY(), 0);
        try {
            InstrumentationRegistry.getInstrumentation().sendPointerSync(down);
            InstrumentationRegistry.getInstrumentation().sendPointerSync(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private AccessibilityNodeInfo findNodeByText(final String text) {
        final AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        final List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        root.recycle();
        AccessibilityNodeInfo match = null;
        for (final AccessibilityNodeInfo node : nodes) {
            if (match == null && node.isVisibleToUser() && fullyVisibleInList(node)
                    && text.equals(String.valueOf(node.getText()))) {
                match = node;
            } else {
                node.recycle();
            }
        }
        return match;
    }

    private boolean fullyVisibleInList(final AccessibilityNodeInfo node) {
        final Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isScrollable()) {
                final Rect viewport = new Rect();
                parent.getBoundsInScreen(viewport);
                parent.recycle();
                return viewport.contains(bounds);
            }
            final AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
        }
        return true;
    }

    private boolean scrollForward(final AccessibilityNodeInfo node) {
        if (node.isScrollable()
                && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            final AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                final boolean scrolled = scrollForward(child);
                child.recycle();
                if (scrolled) {
                    return true;
                }
            }
        }
        return false;
    }

    private void captureScreen(final String name) throws IOException {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // Surface rotation/dialog animations can outlive the activity's idle queue.
        SystemClock.sleep(800);
        final Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        assertTrue("could not capture screen", screenshot != null);
        final File externalFiles = context.getExternalFilesDir(null);
        assertTrue("external files directory was unavailable", externalFiles != null);
        final File directory = new File(externalFiles, SCREENSHOT_DIRECTORY);
        assertTrue("could not create screenshot directory",
                directory.exists() || directory.mkdirs());
        final File output = new File(directory, name);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue("could not write screenshot", screenshot.compress(
                    Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            screenshot.recycle();
        }
        assertTrue("screenshot was empty", output.length() > 0);
    }

    private void finishQueueActivity() {
        runOnMain(() -> {
            for (final android.app.Activity activity : List.copyOf(
                    ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED))) {
                if (activity instanceof PlayQueueActivity || activity instanceof MainActivity) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    activity.finish();
                }
            }
        });
    }

    private void initPlayerForLocalAudio() throws Exception {
        final Method initPlayer = org.schabi.newpipe.player.Player.class
                .getDeclaredMethod("initPlayer", boolean.class);
        initPlayer.setAccessible(true);
        initPlayer.invoke(player, false);
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = org.schabi.newpipe.player.Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    private File createSilentWav() throws IOException {
        final File wav = File.createTempFile("personal-playback-", ".wav", context.getCacheDir());
        final int sampleRate = 8000;
        final int seconds = 4;
        final int dataLength = sampleRate * seconds * 2;
        try (FileOutputStream output = new FileOutputStream(wav)) {
            writeAscii(output, "RIFF");
            writeInt(output, 36 + dataLength);
            writeAscii(output, "WAVEfmt ");
            writeInt(output, 16);
            writeShort(output, 1);
            writeShort(output, 1);
            writeInt(output, sampleRate);
            writeInt(output, sampleRate * 2);
            writeShort(output, 2);
            writeShort(output, 16);
            writeAscii(output, "data");
            writeInt(output, dataLength);
            output.write(new byte[dataLength]);
        }
        return wav;
    }

    private static void writeAscii(final FileOutputStream output, final String value)
            throws IOException {
        output.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeInt(final FileOutputStream output, final int value)
            throws IOException {
        output.write(value);
        output.write(value >>> 8);
        output.write(value >>> 16);
        output.write(value >>> 24);
    }

    private static void writeShort(final FileOutputStream output, final int value)
            throws IOException {
        output.write(value);
        output.write(value >>> 8);
    }

    private static boolean waitFor(final Check check, final long timeoutSeconds)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (check.matches()) {
                return true;
            }
            Thread.sleep(25);
        }
        return check.matches();
    }

    private static void runOnMain(final ThrowingRunnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                action.run();
            } catch (final Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private static <T> T callOnMain(final ThrowingSupplier<T> action) {
        final Object[] result = new Object[1];
        runOnMain(() -> result[0] = action.get());
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    private interface Check {
        boolean matches();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
