package org.schabi.newpipe.player;

import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NO_PERMISSION;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_TIMEOUT;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_UNSPECIFIED;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_INTERNAL;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_REMOVE;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SKIP;
import static com.google.android.exoplayer2.Player.DiscontinuityReason;
import static com.google.android.exoplayer2.Player.Listener;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ONE;
import static com.google.android.exoplayer2.Player.RepeatMode;
import static org.schabi.newpipe.extractor.ServiceList.YouTube;
import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;
import static org.schabi.newpipe.player.helper.PlayerHelper.retrievePlaybackParametersFromPrefs;
import static org.schabi.newpipe.player.helper.PlayerHelper.retrieveSeekDurationFromPreferences;
import static org.schabi.newpipe.player.helper.PlayerHelper.savePlaybackParametersToPrefs;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_CLOSE;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_FAST_FORWARD;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_FAST_REWIND;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_NEXT;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_PAUSE;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_PREVIOUS;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_RECREATE_NOTIFICATION;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_REPEAT;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_SHUFFLE;
import static org.schabi.newpipe.util.ListHelper.getPopupResolutionIndex;
import static org.schabi.newpipe.util.ListHelper.getResolutionIndex;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import static coil3.Image_androidKt.toBitmap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import androidx.core.math.MathUtils;
import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player.PositionInfo;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.video.VideoSize;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.event.PlayerEventListener;
import org.schabi.newpipe.player.event.PlayerServiceEventListener;
import org.schabi.newpipe.player.helper.AudioReactor;
import org.schabi.newpipe.player.helper.CustomRenderersFactory;
import org.schabi.newpipe.player.helper.LoadController;
import org.schabi.newpipe.player.helper.LastPlaybackSessionStore;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.helper.PlaybackRecovery;
import org.schabi.newpipe.player.helper.RecommendationExclusions;
import org.schabi.newpipe.player.helper.SleepTimer;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineMediaTag;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.ExceptionTag;
import org.schabi.newpipe.player.mediasession.MediaSessionPlayerUi;
import org.schabi.newpipe.player.notification.NotificationPlayerUi;
import org.schabi.newpipe.player.playback.MediaSourceManager;
import org.schabi.newpipe.player.playback.PlaybackListener;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.resolver.AudioPlaybackResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver.SourceType;
import org.schabi.newpipe.player.ui.BackgroundPlayerUi;
import org.schabi.newpipe.player.ui.MainPlayerUi;
import org.schabi.newpipe.player.ui.PlayerUi;
import org.schabi.newpipe.player.ui.PlayerUiList;
import org.schabi.newpipe.player.ui.PopupPlayerUi;
import org.schabi.newpipe.player.ui.VideoPlayerUi;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.InfoCache;
import org.schabi.newpipe.util.DataSaver;
import org.schabi.newpipe.player.mediasource.FailedMediaSource;
import org.schabi.newpipe.player.mediasource.FailedMediaSource.AudioOnlyUnavailableException;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.SerializedCache;
import org.schabi.newpipe.util.StreamTypeUtil;
import org.schabi.newpipe.util.image.CoilHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import coil3.target.Target;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class Player implements PlaybackListener, Listener {
    public static final boolean DEBUG = MainActivity.DEBUG;
    public static final String TAG = Player.class.getSimpleName();

    /*//////////////////////////////////////////////////////////////////////////
    // States
    //////////////////////////////////////////////////////////////////////////*/

    public static final int STATE_PREFLIGHT = -1;
    public static final int STATE_BLOCKED = 123;
    public static final int STATE_PLAYING = 124;
    public static final int STATE_BUFFERING = 125;
    public static final int STATE_PAUSED = 126;
    public static final int STATE_PAUSED_SEEK = 127;
    public static final int STATE_COMPLETED = 128;

    /*//////////////////////////////////////////////////////////////////////////
    // Intent
    //////////////////////////////////////////////////////////////////////////*/

    public static final String PLAYBACK_QUALITY = "playback_quality";
    public static final String PLAY_QUEUE_KEY = "play_queue_key";
    public static final String RESUME_PLAYBACK = "resume_playback";
    public static final String PLAY_WHEN_READY = "play_when_ready";
    public static final String PLAYER_TYPE = "player_type";
    public static final String PLAYER_INTENT_TYPE = "player_intent_type";
    public static final String PLAYER_INTENT_DATA = "player_intent_data";

    /*//////////////////////////////////////////////////////////////////////////
    // Time constants
    //////////////////////////////////////////////////////////////////////////*/

    public static final int PROGRESS_LOOP_INTERVAL_MILLIS = 1000; // 1 second

    /*//////////////////////////////////////////////////////////////////////////
    // Other constants
    //////////////////////////////////////////////////////////////////////////*/

    public static final int RENDERER_UNAVAILABLE = -1;

    /*//////////////////////////////////////////////////////////////////////////
    // Playback
    //////////////////////////////////////////////////////////////////////////*/

    // play queue might be null e.g. while player is starting
    @Nullable
    private PlayQueue playQueue;

    @Nullable
    private MediaSourceManager playQueueManager;

    @Nullable
    private PlayQueueItem currentItem;
    @Nullable
    private MediaItemTag currentMetadata;
    @Nullable
    private Bitmap currentThumbnail;
    @Nullable
    private coil3.request.Disposable thumbnailDisposable;

    /*//////////////////////////////////////////////////////////////////////////
    // Player
    //////////////////////////////////////////////////////////////////////////*/

    private ExoPlayer simpleExoPlayer;
    private AudioReactor audioReactor;

    @NonNull
    private final DefaultTrackSelector trackSelector;
    @NonNull
    private final LoadController loadController;
    @NonNull
    private final DefaultRenderersFactory renderFactory;

    @NonNull
    private final VideoPlaybackResolver videoResolver;
    @NonNull
    private final AudioPlaybackResolver audioResolver;

    private final PlayerService service; //TODO try to remove and replace everything with context

    /*//////////////////////////////////////////////////////////////////////////
    // Player states
    //////////////////////////////////////////////////////////////////////////*/

    private PlayerType playerType = PlayerType.MAIN;
    private int currentState = STATE_PREFLIGHT;

    // audio only mode does not mean that player type is background, but that the player was
    // minimized to background but will resume automatically to the original player type
    private boolean isAudioOnly = false;
    private boolean listeningMode;
    private boolean isPrepared = false;

    /*//////////////////////////////////////////////////////////////////////////
    // UIs, listeners and disposables
    //////////////////////////////////////////////////////////////////////////*/

    @SuppressWarnings({"MemberName", "java:S116"}) // keep the unusual member name
    private final PlayerUiList UIs;

    private BroadcastReceiver broadcastReceiver;
    private IntentFilter intentFilter;
    @Nullable
    private PlayerServiceEventListener fragmentListener = null;
    @Nullable
    private PlayerEventListener activityListener = null;

    @NonNull
    private final SerialDisposable progressUpdateDisposable = new SerialDisposable();
    @NonNull
    private final CompositeDisposable databaseUpdateDisposable = new CompositeDisposable();
    @NonNull
    private final CompositeDisposable streamItemDisposable = new CompositeDisposable();

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull
    private final Context context;
    @NonNull
    private final SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener dataSaverListener;
    private final ConnectivityManager connectivityManager;
    private final Handler networkHandler = new Handler(Looper.getMainLooper());
    private String observedAudioQuality;
    private boolean destroyed;
    private final PlaybackRecovery playbackRecovery = new PlaybackRecovery();
    private final SerialDisposable recoveryRequest = new SerialDisposable();
    private boolean recoveryFailed;
    private boolean recoveryReloading;
    private boolean recoveryRefreshingSource;
    private boolean recoveryCanceled;
    private Network audioQualityNetwork;
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull final Network network) {
                    networkHandler.post(() -> {
                        if (destroyed) {
                            return;
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                                || network.equals(connectivityManager.getActiveNetwork())) {
                            audioQualityNetwork = network;
                        }
                        // Before API 26 initial capabilities are not guaranteed after availability.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                            ListHelper.setPlayerNetworkMetered(null);
                            refreshNetworkAudioQuality();
                        }
                    });
                }

                @Override
                public void onCapabilitiesChanged(@NonNull final Network network,
                                                  @NonNull final NetworkCapabilities capabilities) {
                    final boolean metered = !capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                    networkHandler.post(() -> {
                        if (destroyed || !network.equals(Build.VERSION.SDK_INT
                                >= Build.VERSION_CODES.N ? audioQualityNetwork
                                : connectivityManager.getActiveNetwork())) {
                            return;
                        }
                        audioQualityNetwork = network;
                        ListHelper.setPlayerNetworkMetered(metered);
                        refreshNetworkAudioQuality();
                    });
                }

                @Override
                public void onLost(@NonNull final Network network) {
                    networkHandler.post(() -> {
                        if (!destroyed && network.equals(audioQualityNetwork)) {
                            audioQualityNetwork = null;
                            ListHelper.setPlayerNetworkMetered(null);
                        }
                    });
                }
            };
    @NonNull
    private final HistoryRecordManager recordManager;
    private final RecommendationExclusions recommendationExclusions;
    private final LastPlaybackSessionStore lastSessionStore;
    private final SerialDisposable sessionQueueChanges = new SerialDisposable();
    private long lastSessionSave;

    private boolean screenOn = true;
    private boolean sleepTimerExpired;
    private boolean userMuted;
    private static final String SLEEP_FADE_KEY = "personal_sleep_fade";
    public enum RecommendationStatus { IDLE, LOADING, READY, EMPTY, ERROR }
    private enum RecommendationAction { NEXT, AUTO_NEXT, PREVIEW, REPLACE }
    private RecommendationStatus recommendationStatus = RecommendationStatus.IDLE;
    private PlayQueueItem recommendationAnchor;
    private PlayQueueItem recommendationPreview;
    private final List<PlayQueueItem> skippedRecommendations = new ArrayList<>();
    private final SerialDisposable recommendationRequest = new SerialDisposable();
    private boolean recommendationAdvancing;
    private final SleepTimer sleepTimer = new SleepTimer(AndroidSchedulers.mainThread(),
            SystemClock::elapsedRealtime, () -> {
                sleepTimerExpired = true;
                pause();
                if (!exoPlayerIsNull()) {
                    simpleExoPlayer.setPauseAtEndOfMediaItems(false);
                }
            }, this::applySleepGain);

    /*//////////////////////////////////////////////////////////////////////////
    // Constructor
    //////////////////////////////////////////////////////////////////////////*/
    //region Constructor

    /**
     * @param service the service this player resides in
     * @param mediaSession used to build the {@link MediaSessionPlayerUi}, lives in the service and
     *                     could possibly be reused with multiple player instances
     * @param sessionConnector used to build the {@link MediaSessionPlayerUi}, lives in the service
     *                         and could possibly be reused with multiple player instances
     */
    public Player(@NonNull final PlayerService service,
                  @NonNull final MediaSessionCompat mediaSession,
                  @NonNull final MediaSessionConnector sessionConnector) {
        this.service = service;
        context = service;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        connectivityManager = ContextCompat.getSystemService(context, ConnectivityManager.class);
        observedAudioQuality = DataSaver.getAudioQuality(context);
        recordManager = new HistoryRecordManager(context);
        recommendationExclusions = new RecommendationExclusions(prefs);
        lastSessionStore = new LastPlaybackSessionStore(prefs);
        sleepTimer.setFadeEnabled(prefs.getBoolean(SLEEP_FADE_KEY, true));

        setupBroadcastReceiver();

        trackSelector = new DefaultTrackSelector(context, PlayerHelper.getQualitySelector());
        final PlayerDataSource dataSource = new PlayerDataSource(context,
                new DefaultBandwidthMeter.Builder(context).build());
        loadController = new LoadController();

        renderFactory = prefs.getBoolean(
                context.getString(
                        R.string.always_use_exoplayer_set_output_surface_workaround_key), false)
                ? new CustomRenderersFactory(context) : new DefaultRenderersFactory(context);

        renderFactory.setEnableDecoderFallback(
                prefs.getBoolean(
                        context.getString(
                                R.string.use_exoplayer_decoder_fallback_key), false));

        videoResolver = new VideoPlaybackResolver(context, dataSource, getQualityResolver());
        audioResolver = new AudioPlaybackResolver(context, dataSource);
        dataSaverListener = (preferences, key) -> {
            if (!context.getString(R.string.data_saver_key).equals(key)
                    && !context.getString(R.string.audio_quality_key).equals(key)) {
                return;
            }
            observedAudioQuality = DataSaver.getAudioQuality(context);
            if (playQueue != null && !exoPlayerIsNull()) {
                saveStreamProgressState();
                setRecovery();
                if (context.getString(R.string.data_saver_key).equals(key)
                        && DataSaver.isEnabled(context) && !audioPlayerSelected()) {
                    ContextCompat.startForegroundService(context,
                            NavigationHelper.getPlayerIntent(context, PlayerService.class,
                                    playQueue, PlayerIntentType.AllOthers)
                                    .putExtra(PLAYER_TYPE, PlayerType.AUDIO)
                                    .putExtra(RESUME_PLAYBACK, true)
                                    .putExtra(PLAY_WHEN_READY, getPlayWhenReady()));
                } else {
                    reloadPlayQueueManager();
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(dataSaverListener);

        // The UIs added here should always be present. They will be initialized when the player
        // reaches the initialization step. Make sure the media session ui is before the
        // notification ui in the UIs list, since the notification depends on the media session in
        // PlayerUi#initPlayer(), and UIs.call() guarantees UI order is preserved.
        UIs = new PlayerUiList(
                new MediaSessionPlayerUi(this, mediaSession, sessionConnector),
                new NotificationPlayerUi(this)
        );
        if (connectivityManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } else {
                connectivityManager.registerNetworkCallback(new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                        networkCallback);
            }
        }
    }

    private void refreshNetworkAudioQuality() {
        if (destroyed || currentMetadata instanceof OfflineMediaTag
                || !DataSaver.isEnabled(context)) {
            return;
        }
        final String quality = DataSaver.getAudioQuality(context);
        if (DEBUG) {
            Log.d(TAG, "Network audio quality: " + observedAudioQuality + " -> " + quality);
        }
        if (quality.equals(observedAudioQuality)) {
            return;
        }
        observedAudioQuality = quality;
        if (playQueue != null && !exoPlayerIsNull()) {
            saveStreamProgressState();
            setRecovery();
            reloadPlayQueueManager();
        }
    }

    private VideoPlaybackResolver.QualityResolver getQualityResolver() {
        return new VideoPlaybackResolver.QualityResolver() {
            @Override
            public int getDefaultResolutionIndex(final List<VideoStream> sortedVideos) {
                return videoPlayerSelected()
                        ? ListHelper.getDefaultResolutionIndex(context, sortedVideos)
                        : ListHelper.getPopupDefaultResolutionIndex(context, sortedVideos);
            }

            @Override
            public int getOverrideResolutionIndex(final List<VideoStream> sortedVideos,
                                                  final String playbackQuality) {
                return videoPlayerSelected()
                        ? getResolutionIndex(context, sortedVideos, playbackQuality)
                        : getPopupResolutionIndex(context, sortedVideos, playbackQuality);
            }
        };
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback initialization via intent
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback initialization via intent

    @SuppressWarnings("MethodLength")
    public void handleIntent(@NonNull final Intent intent) {
        final var playerIntentType = IntentCompat.getSerializableExtra(intent, PLAYER_INTENT_TYPE,
                PlayerIntentType.class);
        if (playerIntentType == null) {
            return;
        }
        if (playerIntentType == PlayerIntentType.AllOthers
                || playerIntentType == PlayerIntentType.TimestampChange) {
            recommendationRequest.set(null);
            sleepTimer.expireIfDue();
            sleepTimerExpired = false;
        }
        // TODO: this should be in the second switch below, but I’m not sure whether I
        // can move the initUIs stuff without breaking the setup for edge cases somehow.
        // when playing from a timestamp, keep the current player as-is.
        if (playerIntentType != PlayerIntentType.TimestampChange) {
            playerType = IntentCompat.getSerializableExtra(intent, PLAYER_TYPE, PlayerType.class);
        }
        initUIsForCurrentPlayerType();
        isAudioOnly = audioPlayerSelected() || listeningMode;

        if (intent.hasExtra(PLAYBACK_QUALITY)) {
            videoResolver.setPlaybackQuality(intent.getStringExtra(PLAYBACK_QUALITY));
        }

        final boolean playWhenReady = intent.getBooleanExtra(PLAY_WHEN_READY, true);

        switch (playerIntentType) {
            case Enqueue -> {
                if (playQueue != null) {
                    final PlayQueue newQueue = getPlayQueueFromCache(intent);
                    if (newQueue == null) {
                        return;
                    }
                    playQueue.append(newQueue.getStreams());
                    return;
                }

                // TODO: This falls through to the old logic, there was no playQueue
                // yet so we should start the player and add the new video
                break;
            }
            case EnqueueNext -> {
                if (playQueue != null) {
                    final PlayQueue newQueue = getPlayQueueFromCache(intent);
                    if (newQueue == null) {
                        return;
                    }
                    final PlayQueueItem newItem = newQueue.getStreams().get(0);
                    playQueue.enqueueNext(newItem, false);
                    return;
                }

                // TODO: This falls through to the old logic, there was no playQueue
                // yet so we should start the player and add the new video
                break;
            }
            case TimestampChange -> {
                final var data = Objects.requireNonNull(IntentCompat.getParcelableExtra(intent,
                        PLAYER_INTENT_DATA, TimestampChangeData.class));
                final Single<StreamInfo> single =
                        ExtractorHelper.getStreamInfo(data.getServiceId(), data.getUrl(), false);
                streamItemDisposable.add(single.subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(info -> {
                            final @Nullable PlayQueue oldPlayQueue = playQueue;
                            info.setStartPosition(data.getSeconds());
                            final PlayQueueItem playQueueItem = new PlayQueueItem(info);

                            // If the stream is already playing,
                            // we can just seek to the appropriate timestamp
                            if (oldPlayQueue != null
                                    && playQueueItem.isSameItem(oldPlayQueue.getItem())) {
                                // Player can have state = IDLE when playback is stopped or failed
                                // and we should retry in this case
                                if (simpleExoPlayer.getPlaybackState()
                                        == com.google.android.exoplayer2.Player.STATE_IDLE) {
                                    simpleExoPlayer.prepare();
                                }
                                simpleExoPlayer.seekTo(oldPlayQueue.getIndex(),
                                        data.getSeconds() * 1000L);
                                simpleExoPlayer.setPlayWhenReady(playWhenReady);

                            } else {
                                final PlayQueue newPlayQueue;

                                // If there is no queue yet, just add our item
                                if (oldPlayQueue == null) {
                                    newPlayQueue = new SinglePlayQueue(playQueueItem);

                                // else we add the timestamped stream behind the current video
                                // and start playing it.
                                } else {
                                    oldPlayQueue.enqueueNext(playQueueItem, true);
                                    oldPlayQueue.offsetIndex(1);
                                    newPlayQueue = oldPlayQueue;
                                }
                                initPlayback(newPlayQueue, playWhenReady);
                            }

                        }, throwable -> {
                            // This will only show a snackbar if the passed context has a root view:
                            // otherwise it will resort to showing a notification, so we are safe
                            // here.
                            final var info = new ErrorInfo(throwable, UserAction.PLAY_ON_POPUP,
                                    data.getUrl(), null, data.getUrl());
                            ErrorUtil.createNotification(context, info);
                        }));
                return;
            }
            case AllOthers -> {
                // fallthrough; TODO: put other intent data in separate cases
            }
        }

        final PlayQueue newQueue = getPlayQueueFromCache(intent);
        if (newQueue == null) {
            return;
        }
        if (sleepTimer.isEndOfItem() && (playQueue == null || playQueue.getItem() == null
                || newQueue.getItem() == null
                || !newQueue.getItem().isSameItem(playQueue.getItem()))) {
            cancelSleepTimer();
        }

        // branching parameters for below
        final boolean samePlayQueue = playQueue != null && playQueue.equalStreamsAndIndex(newQueue);

        /*
         * TODO As seen in #7427 this does not work:
         * There are 3 situations when playback shouldn't be started from scratch (zero timestamp):
         * 1. User pressed on a timestamp link and the same video should be rewound to the timestamp
         * 2. User changed a player from, for example. main to popup, or from audio to main, etc
         * 3. User chose to resume a video based on a saved timestamp from history of played videos
         * In those cases time will be saved because re-init of the play queue is a not an instant
         *  task and requires network calls
         * */
        // seek to timestamp if stream is already playing
        if (!exoPlayerIsNull()
                && newQueue.size() == 1 && newQueue.getItem() != null
                && playQueue != null && playQueue.size() == 1 && playQueue.getItem() != null
                && newQueue.getItem().isSameItem(playQueue.getItem())
                && newQueue.getItem().getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET) {
            // Player can have state = IDLE when playback is stopped or failed
            // and we should retry in this case
            if (simpleExoPlayer.getPlaybackState()
                    == com.google.android.exoplayer2.Player.STATE_IDLE) {
                simpleExoPlayer.prepare();
            }
            simpleExoPlayer.seekTo(playQueue.getIndex(), newQueue.getItem().getRecoveryPosition());
            simpleExoPlayer.setPlayWhenReady(playWhenReady);

        } else if (!exoPlayerIsNull()
                && samePlayQueue
                && playQueue != null
                && !playQueue.isDisposed()) {
            // Do not re-init the same PlayQueue. Save time
            // Player can have state = IDLE when playback is stopped or failed
            // and we should retry in this case
            if (simpleExoPlayer.getPlaybackState()
                    == com.google.android.exoplayer2.Player.STATE_IDLE) {
                simpleExoPlayer.prepare();
            }
            simpleExoPlayer.setPlayWhenReady(playWhenReady);

        } else if (intent.getBooleanExtra(RESUME_PLAYBACK, false)
                && DependentPreferenceHelper.getResumePlaybackEnabled(context)
                // !samePlayQueue
                && (playQueue == null || !playQueue.equalStreamsAndIndex(newQueue))
                && !newQueue.isEmpty()
                && newQueue.getItem() != null
                && newQueue.getItem().getRecoveryPosition() == PlayQueueItem.RECOVERY_UNSET) {
            databaseUpdateDisposable.add(recordManager.loadStreamState(newQueue.getItem())
                    .observeOn(AndroidSchedulers.mainThread())
                    // Do not place initPlayback() in doFinally() because
                    // it restarts playback after destroy()
                    //.doFinally()
                    .subscribe(
                            state -> {
                                if (!state.isFinished(newQueue.getItem().getDuration())) {
                                    // resume playback only if the stream was not played to the end
                                    newQueue.setRecovery(newQueue.getIndex(),
                                            state.getProgressMillis());
                                }
                                initPlayback(newQueue, playWhenReady);
                            },
                            error -> {
                                if (DEBUG) {
                                    Log.w(TAG, "Failed to start playback", error);
                                }
                                // In case any error we can start playback without history
                                initPlayback(newQueue, playWhenReady);
                            },
                            () -> {
                                // Completed but not found in history
                                initPlayback(newQueue, playWhenReady);
                            }
                    ));
        } else {
            // Good to go...
            // In a case of equal PlayQueues we can re-init old one but only when it is disposed
            initPlayback(samePlayQueue ? playQueue : newQueue, playWhenReady);
        }

    }


    public void handleIntentPost(final PlayerType oldPlayerType) {
        if (oldPlayerType != playerType && playQueue != null) {
            // If playerType changes from one to another we should reload the player
            // (to disable/enable video stream or to set quality)
            reloadPlayQueueManager();
        }

        UIs.call(PlayerUi::setupAfterIntent);
        NavigationHelper.sendPlayerStartedEvent(context);
    }

    @Nullable
    private static PlayQueue getPlayQueueFromCache(@NonNull final Intent intent) {
        final String queueCache = intent.getStringExtra(PLAY_QUEUE_KEY);
        if (queueCache == null) {
            return null;
        }
        return SerializedCache.getInstance().take(queueCache, PlayQueue.class);
    }

    private void initUIsForCurrentPlayerType() {
        if ((UIs.get(MainPlayerUi.class).isPresent() && playerType == PlayerType.MAIN)
                || (UIs.get(BackgroundPlayerUi.class).isPresent() && playerType == PlayerType.AUDIO)
                || (UIs.get(PopupPlayerUi.class).isPresent() && playerType == PlayerType.POPUP)) {
            // correct UI already in place
            return;
        }

        // try to reuse binding if possible
        final PlayerBinding binding = UIs.get(VideoPlayerUi.class).map(VideoPlayerUi::getBinding)
                .orElseGet(() -> {
                    if (playerType == PlayerType.AUDIO) {
                        return null;
                    } else {
                        return PlayerBinding.inflate(LayoutInflater.from(context));
                    }
                });

        switch (playerType) {
            case MAIN:
                UIs.destroyAll(PopupPlayerUi.class);
                UIs.destroyAll(BackgroundPlayerUi.class);
                UIs.addAndPrepare(new MainPlayerUi(this, binding));
                break;
            case POPUP:
                UIs.destroyAll(MainPlayerUi.class);
                UIs.destroyAll(BackgroundPlayerUi.class);
                UIs.addAndPrepare(new PopupPlayerUi(this, binding));
                break;
            case AUDIO:
                UIs.destroyAll(VideoPlayerUi.class); // destroys both MainPlayerUi and PopupPlayerUi
                UIs.addAndPrepare(new BackgroundPlayerUi(this));
                break;
        }
    }

    private void initPlayback(@NonNull final PlayQueue queue,
                              final boolean playOnReady) {
        destroyPlayer();
        initPlayer(playOnReady);
        final boolean playbackSkipSilence = getPrefs().getBoolean(getContext().getString(
                R.string.playback_skip_silence_key), getPlaybackSkipSilence());
        final PlaybackParameters savedParameters = retrievePlaybackParametersFromPrefs(this);
        setPlaybackParameters(savedParameters.speed, savedParameters.pitch, playbackSkipSilence);

        playQueue = queue;
        playQueue.init();
        reloadPlayQueueManager();

        UIs.call(PlayerUi::initPlayback);

        sessionQueueChanges.set(playQueue.getBroadcastReceiver()
                .subscribe(event -> saveLastSession(),
                        error -> Log.w(TAG, "Could not observe playback session", error)));

        applySleepGain(sleepTimer.getGain());
        notifyQueueUpdateToListeners();
    }

    private void initPlayer(final boolean playOnReady) {
        if (DEBUG) {
            Log.d(TAG, "initPlayer() called with: playOnReady = [" + playOnReady + "]");
        }

        simpleExoPlayer = new ExoPlayer.Builder(context, renderFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadController)
                .setUsePlatformDiagnostics(false)
                .build();
        simpleExoPlayer.addListener(this);
        sleepTimer.expireIfDue();
        simpleExoPlayer.setPlayWhenReady(playOnReady && !sleepTimerExpired);
        simpleExoPlayer.setSeekParameters(PlayerHelper.getSeekParameters(context));
        simpleExoPlayer.setWakeMode(C.WAKE_MODE_NETWORK);
        simpleExoPlayer.setHandleAudioBecomingNoisy(true);

        audioReactor = new AudioReactor(context, simpleExoPlayer);
        applySleepTimerMode();
        applySleepGain(sleepTimer.getGain());

        registerBroadcastReceiver();

        // Setup UIs
        UIs.call(PlayerUi::initPlayer);

        // Disable media tunneling if requested by the user from ExoPlayer settings
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.disable_media_tunneling_key), false)) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setTunnelingEnabled(true));
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Destroy and recovery
    //////////////////////////////////////////////////////////////////////////*/
    //region Destroy and recovery

    private void destroyPlayer() {
        cancelPlaybackRecovery();
        playbackRecovery.reset();
        recoveryFailed = false;
        recoveryCanceled = false;
        recoveryRefreshingSource = false;
        recoveryReloading = false;
        sessionQueueChanges.set(null);
        recommendationRequest.set(null);
        if (DEBUG) {
            Log.d(TAG, "destroyPlayer() called");
        }
        UIs.call(PlayerUi::destroyPlayer);

        if (audioReactor != null) {
            audioReactor.dispose();
            audioReactor = null;
        }

        if (!exoPlayerIsNull()) {
            simpleExoPlayer.removeListener(this);
            simpleExoPlayer.stop();
            simpleExoPlayer.release();
        }
        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }
        if (playQueue != null) {
            playQueue.dispose();
        }
        if (playQueueManager != null) {
            playQueueManager.dispose();
        }
    }

    public void destroy() {
        destroyed = true;
        if (connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        networkHandler.removeCallbacksAndMessages(null);
        ListHelper.setPlayerNetworkMetered(null);
        prefs.unregisterOnSharedPreferenceChangeListener(dataSaverListener);
        sleepTimer.cancel();
        if (DEBUG) {
            Log.d(TAG, "destroy() called");
        }

        saveStreamProgressState();
        saveLastSession();
        setRecovery();
        stopActivityBinding();

        destroyPlayer();
        unregisterBroadcastReceiver();

        databaseUpdateDisposable.clear();
        progressUpdateDisposable.set(null);
        streamItemDisposable.clear();

        UIs.destroyAll(Object.class); // destroy every UI: obviously every UI extends Object
    }

    public void setRecovery() {
        if (playQueue == null || exoPlayerIsNull()) {
            return;
        }

        final int queuePos = playQueue.getIndex();
        final long windowPos = simpleExoPlayer.getCurrentPosition();
        final long duration = simpleExoPlayer.getDuration();

        // No checks due to https://github.com/TeamNewPipe/NewPipe/pull/7195#issuecomment-962624380
        setRecovery(queuePos, MathUtils.clamp(windowPos, 0, duration));
    }

    private void setRecovery(final int queuePos, final long windowPos) {
        if (playQueue == null || playQueue.size() <= queuePos) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Setting recovery, queue: " + queuePos + ", pos: " + windowPos);
        }
        playQueue.setRecovery(queuePos, windowPos);
    }

    public void reloadPlayQueueManager() {
        if (playQueueManager != null) {
            playQueueManager.dispose();
        }

        if (playQueue != null) {
            playQueueManager = new MediaSourceManager(this, playQueue);
        }
    }

    @Override // own playback listener
    public void onPlaybackShutdown() {
        if (DEBUG) {
            Log.d(TAG, "onPlaybackShutdown() called");
        }
        // destroys the service, which in turn will destroy the player
        service.destroyPlayerAndStopService();
    }

    public void smoothStopForImmediateReusing() {
        // Pausing would make transition from one stream to a new stream not smooth, so only stop
        simpleExoPlayer.stop();
        setRecovery();
        UIs.call(PlayerUi::smoothStopForImmediateReusing);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Broadcast receiver
    //////////////////////////////////////////////////////////////////////////*/
    //region Broadcast receiver

    /**
     * This function prepares the broadcast receiver and is called only in the constructor.
     * Therefore if you want any PlayerUi to receive a broadcast action, you should add it here,
     * even if that player ui might never be added to the player. In that case the received
     * broadcast would not do anything.
     */
    private void setupBroadcastReceiver() {
        if (DEBUG) {
            Log.d(TAG, "setupBroadcastReceiver() called");
        }

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context ctx, final Intent intent) {
                onBroadcastReceived(intent);
            }
        };
        intentFilter = new IntentFilter();

        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

        intentFilter.addAction(ACTION_CLOSE);
        intentFilter.addAction(ACTION_PLAY_PAUSE);
        intentFilter.addAction(ACTION_PLAY_PREVIOUS);
        intentFilter.addAction(ACTION_PLAY_NEXT);
        intentFilter.addAction(ACTION_FAST_REWIND);
        intentFilter.addAction(ACTION_FAST_FORWARD);
        intentFilter.addAction(ACTION_REPEAT);
        intentFilter.addAction(ACTION_SHUFFLE);
        intentFilter.addAction(ACTION_RECREATE_NOTIFICATION);

        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_FRAGMENT_RESUMED);
        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_FRAGMENT_STOPPED);

        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_HEADSET_PLUG);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        }
    }

    private void onBroadcastReceived(final Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "onBroadcastReceived() called with: intent = [" + intent + "]");
        }

        switch (intent.getAction()) {
            case ConnectivityManager.CONNECTIVITY_ACTION:
                // API 23 generic callbacks do not report switches between existing networks.
                ListHelper.setPlayerNetworkMetered(null);
                if (connectivityManager != null) {
                    audioQualityNetwork = connectivityManager.getActiveNetwork();
                    final NetworkCapabilities capabilities = audioQualityNetwork == null ? null
                            : connectivityManager.getNetworkCapabilities(audioQualityNetwork);
                    if (capabilities != null) {
                        ListHelper.setPlayerNetworkMetered(!capabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                    }
                }
                refreshNetworkAudioQuality();
                break;
            case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                pause();
                break;
            case ACTION_CLOSE:
                service.destroyPlayerAndStopService();
                break;
            case ACTION_PLAY_PAUSE:
                playPause();
                break;
            case ACTION_PLAY_PREVIOUS:
                playPrevious();
                break;
            case ACTION_PLAY_NEXT:
                playNext();
                break;
            case ACTION_FAST_REWIND:
                fastRewind();
                break;
            case ACTION_FAST_FORWARD:
                fastForward();
                break;
            case ACTION_REPEAT:
                cycleNextRepeatMode();
                break;
            case ACTION_SHUFFLE:
                toggleShuffleModeEnabled();
                break;
            case Intent.ACTION_SCREEN_OFF:
                screenOn = false;
                break;
            case Intent.ACTION_SCREEN_ON:
                screenOn = true;
                sleepTimer.expireIfDue();
                break;
            case Intent.ACTION_CONFIGURATION_CHANGED:
                if (DEBUG) {
                    Log.d(TAG, "ACTION_CONFIGURATION_CHANGED received");
                }
                break;
        }

        UIs.call(playerUi -> playerUi.onBroadcastReceived(intent));
    }

    private void registerBroadcastReceiver() {
        // Try to unregister current first
        unregisterBroadcastReceiver();
        ContextCompat.registerReceiver(context, broadcastReceiver, intentFilter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    private void unregisterBroadcastReceiver() {
        try {
            context.unregisterReceiver(broadcastReceiver);
        } catch (final IllegalArgumentException unregisteredException) {
            Log.w(TAG, "Broadcast receiver already unregistered: "
                    + unregisteredException.getMessage());
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Thumbnail loading
    //////////////////////////////////////////////////////////////////////////*/
    //region Thumbnail loading

    private void loadCurrentThumbnail(final List<Image> thumbnails) {
        if (DEBUG) {
            Log.d(TAG, "Thumbnail - loadCurrentThumbnail() called with thumbnails = ["
                    + thumbnails.size() + "]");
        }

        // Cancel any ongoing image loading
        if (thumbnailDisposable != null) {
            thumbnailDisposable.dispose();
        }

        // Unset currentThumbnail, since it is now outdated. This ensures it is not used in media
        // session metadata while the new thumbnail is being loaded by Coil.
        onThumbnailLoaded(null);
        if (thumbnails.isEmpty()) {
            return;
        }

        // scale down the notification thumbnail for performance
        final var thumbnailTarget = new Target() {
            @Override
            public void onError(@Nullable final coil3.Image error) {
                Log.e(TAG, "Thumbnail - onError() called");
                // there is a new thumbnail, so e.g. the end screen thumbnail needs to change, too.
                onThumbnailLoaded(null);
            }

            @Override
            public void onStart(@Nullable final coil3.Image placeholder) {
                if (DEBUG) {
                    Log.d(TAG, "Thumbnail - onStart() called");
                }
            }

            @Override
            public void onSuccess(@NonNull final coil3.Image result) {
                if (DEBUG) {
                    Log.d(TAG, "Thumbnail - onSuccess() called with: drawable = [" + result + "]");
                }
                // there is a new thumbnail, so e.g. the end screen thumbnail needs to change, too.
                onThumbnailLoaded(toBitmap(result));
            }
        };
        thumbnailDisposable = CoilHelper.INSTANCE
                .loadScaledDownThumbnail(context, thumbnails, thumbnailTarget);
    }


    private void onThumbnailLoaded(@Nullable final Bitmap bitmap) {
        // Avoid useless thumbnail updates, if the thumbnail has not actually changed. Based on the
        // thumbnail loading code, this if would be skipped only when both bitmaps are `null`, since
        // onThumbnailLoaded won't be called twice with the same nonnull bitmap by Coil's target.
        if (currentThumbnail != bitmap) {
            currentThumbnail = bitmap;
            UIs.call(playerUi -> playerUi.onThumbnailLoaded(bitmap));
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback parameters
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback parameters

    public float getPlaybackSpeed() {
        return getPlaybackParameters().speed;
    }

    public void setPlaybackSpeed(final float speed) {
        setPlaybackParameters(speed, getPlaybackPitch(), getPlaybackSkipSilence());
    }

    public float getPlaybackPitch() {
        return getPlaybackParameters().pitch;
    }

    public boolean getPlaybackSkipSilence() {
        return !exoPlayerIsNull() && simpleExoPlayer.getSkipSilenceEnabled();
    }

    public PlaybackParameters getPlaybackParameters() {
        if (exoPlayerIsNull()) {
            return PlaybackParameters.DEFAULT;
        }
        return simpleExoPlayer.getPlaybackParameters();
    }

    /**
     * Sets the playback parameters of the player, and also saves them to shared preferences.
     * Speed and pitch are rounded up to 2 decimal places before being used or saved.
     *
     * @param speed       the playback speed, will be rounded to up to 2 decimal places
     * @param pitch       the playback pitch, will be rounded to up to 2 decimal places
     * @param skipSilence skip silence during playback
     */
    public void setPlaybackParameters(final float speed, final float pitch,
                                      final boolean skipSilence) {
        final float roundedSpeed = Math.round(speed * 100.0f) / 100.0f;
        final float roundedPitch = Math.round(pitch * 100.0f) / 100.0f;

        savePlaybackParametersToPrefs(this, roundedSpeed, roundedPitch, skipSilence);
        simpleExoPlayer.setPlaybackParameters(
                new PlaybackParameters(roundedSpeed, roundedPitch));
        simpleExoPlayer.setSkipSilenceEnabled(skipSilence);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Progress loop and updates
    //////////////////////////////////////////////////////////////////////////*/
    //region Progress loop and updates

    private void onUpdateProgress(final int currentProgress,
                                  final int duration,
                                  final int bufferPercent) {
        if (SystemClock.elapsedRealtime() - lastSessionSave >= 5000) {
            saveLastSession();
        }
        if (sleepTimer.isEndOfItem() && duration > 0) {
            sleepTimer.updateItemTimeRemaining((long) (Math.max(0, duration - currentProgress)
                    / Math.max(0.1f, getPlaybackSpeed())));
        }
        if (isPrepared) {
            UIs.call(ui -> ui.onUpdateProgress(currentProgress, duration, bufferPercent));
            notifyProgressUpdateToListeners(currentProgress, duration, bufferPercent);
        }
    }

    public void startProgressLoop() {
        progressUpdateDisposable.set(getProgressUpdateDisposable());
    }

    private void stopProgressLoop() {
        progressUpdateDisposable.set(null);
    }

    public boolean isProgressLoopRunning() {
        return progressUpdateDisposable.get() != null;
    }

    public void triggerProgressUpdate() {
        if (exoPlayerIsNull()) {
            return;
        }

        onUpdateProgress(Math.max((int) simpleExoPlayer.getCurrentPosition(), 0),
                (int) simpleExoPlayer.getDuration(), simpleExoPlayer.getBufferedPercentage());
    }

    private Disposable getProgressUpdateDisposable() {
        return Observable.interval(PROGRESS_LOOP_INTERVAL_MILLIS, MILLISECONDS,
                        AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> triggerProgressUpdate(),
                        error -> Log.e(TAG, "Progress update failure: ", error));
    }

    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Playback states
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback states
    @Override
    public void onPlayWhenReadyChanged(final boolean playWhenReady, final int reason) {
        if (!playWhenReady && reason == com.google.android.exoplayer2.Player
                .PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            sleepTimer.onItemEnded();
        }
        if (!playWhenReady) {
            // Audio focus can pause ExoPlayer directly. Keep its recovery source available
            // for a valid focus gain; explicit pause/stop/timer actions cancel separately.
            recommendationRequest.set(null);
        }
        if (playWhenReady) {
            recoveryCanceled = false;
        }
        if (playWhenReady && sleepTimer.expireIfDue()) {
            return;
        }
        if (playWhenReady && sleepTimerExpired) {
            simpleExoPlayer.pause();
            return;
        }
        // Existing-queue and timestamp actions can set ExoPlayer's play intent directly.
        if (playWhenReady && audioReactor != null && !isMuted() && !audioReactor.hasAudioFocus()
                && !audioReactor.requestAudioFocus()) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onPlayWhenReadyChanged() called with: "
                    + "playWhenReady = [" + playWhenReady + "], "
                    + "reason = [" + reason + "]");
        }
        final int playbackState = exoPlayerIsNull()
                ? com.google.android.exoplayer2.Player.STATE_IDLE
                : simpleExoPlayer.getPlaybackState();
        updatePlaybackState(playWhenReady, playbackState);
    }

    @Override
    public void onPlaybackStateChanged(final int playbackState) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onPlaybackStateChanged() called with: "
                    + "playbackState = [" + playbackState + "]");
        }
        updatePlaybackState(getPlayWhenReady(), playbackState);
    }

    private void updatePlaybackState(final boolean playWhenReady, final int playbackState) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - updatePlaybackState() called with: "
                    + "playWhenReady = [" + playWhenReady + "], "
                    + "playbackState = [" + playbackState + "]");
        }

        if (currentState == STATE_PAUSED_SEEK) {
            if (DEBUG) {
                Log.d(TAG, "updatePlaybackState() is currently blocked");
            }
            return;
        }

        switch (playbackState) {
            case com.google.android.exoplayer2.Player.STATE_IDLE: // 1
                isPrepared = false;
                break;
            case com.google.android.exoplayer2.Player.STATE_BUFFERING: // 2
                if (isPrepared) {
                    changeState(STATE_BUFFERING);
                }
                break;
            case com.google.android.exoplayer2.Player.STATE_READY: //3
                if (currentMetadata != null && !(currentMetadata instanceof ExceptionTag)
                        && !recoveryFailed) {
                    recoveryReloading = false;
                    recoveryRefreshingSource = false;
                    recoveryRequest.set(null);
                    playbackRecovery.recovered();
                }
                if (!isPrepared) {
                    isPrepared = true;
                    onPrepared(playWhenReady);
                }
                // onPrepared may have paused playback after a denied focus request.
                changeState(getPlayWhenReady() ? STATE_PLAYING : STATE_PAUSED);
                break;
            case com.google.android.exoplayer2.Player.STATE_ENDED: // 4
                sleepTimer.onItemEnded();
                changeState(STATE_COMPLETED);
                saveStreamProgressStateCompleted();
                isPrepared = false;
                break;
        }
    }

    @Override // exoplayer listener
    public void onIsLoadingChanged(final boolean isLoading) {
        if (!isLoading && currentState == STATE_PAUSED && isProgressLoopRunning()) {
            stopProgressLoop();
        } else if (isLoading && !isProgressLoopRunning()) {
            startProgressLoop();
        }
    }

    @Override // own playback listener
    public void onPlaybackBlock() {
        if (exoPlayerIsNull()) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Playback - onPlaybackBlock() called");
        }

        currentItem = null;
        currentMetadata = null;
        simpleExoPlayer.stop();
        isPrepared = false;

        changeState(STATE_BLOCKED);
    }

    @Override // own playback listener
    public void onPlaybackUnblock(final MediaSource mediaSource) {
        if (DEBUG) {
            Log.d(TAG, "Playback - onPlaybackUnblock() called");
        }

        if (exoPlayerIsNull()) {
            return;
        }
        if (currentState == STATE_BLOCKED) {
            changeState(STATE_BUFFERING);
        }
        simpleExoPlayer.setMediaSource(mediaSource, false);
        simpleExoPlayer.prepare();
    }

    public void changeState(final int state) {
        if (DEBUG) {
            Log.d(TAG, "changeState() called with: state = [" + state + "]");
        }
        currentState = state;
        switch (state) {
            case STATE_BLOCKED:
                onBlocked();
                break;
            case STATE_PLAYING:
                onPlaying();
                break;
            case STATE_BUFFERING:
                onBuffering();
                break;
            case STATE_PAUSED:
                onPaused();
                break;
            case STATE_PAUSED_SEEK:
                onPausedSeek();
                break;
            case STATE_COMPLETED:
                onCompleted();
                break;
        }
        notifyPlaybackUpdateToListeners();
    }

    private void onPrepared(final boolean playWhenReady) {
        if (DEBUG) {
            Log.d(TAG, "onPrepared() called with: playWhenReady = [" + playWhenReady + "]");
        }

        UIs.call(PlayerUi::onPrepared);
        // A restored paused player stops its progress loop as soon as it is ready.
        triggerProgressUpdate();

        if (playWhenReady && !isMuted() && !audioReactor.hasAudioFocus()) {
            audioReactor.requestAudioFocus();
        }
    }

    private void onBlocked() {
        if (DEBUG) {
            Log.d(TAG, "onBlocked() called");
        }
        if (!isProgressLoopRunning()) {
            startProgressLoop();
        }

        UIs.call(PlayerUi::onBlocked);
    }

    private void onPlaying() {
        if (DEBUG) {
            Log.d(TAG, "onPlaying() called");
        }
        if (!isProgressLoopRunning()) {
            startProgressLoop();
        }

        UIs.call(PlayerUi::onPlaying);
    }

    private void onBuffering() {
        if (DEBUG) {
            Log.d(TAG, "onBuffering() called");
        }

        UIs.call(PlayerUi::onBuffering);
    }

    private void onPaused() {
        saveLastSession();
        if (DEBUG) {
            Log.d(TAG, "onPaused() called");
        }

        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }

        UIs.call(PlayerUi::onPaused);
    }

    private void onPausedSeek() {
        if (DEBUG) {
            Log.d(TAG, "onPausedSeek() called");
        }
        UIs.call(PlayerUi::onPausedSeek);
    }

    private void onCompleted() {
        if (DEBUG) {
            Log.d(TAG, "onCompleted() called" + (playQueue == null ? ". playQueue is null" : ""));
        }
        if (playQueue == null) {
            return;
        }

        UIs.call(PlayerUi::onCompleted);

        if (sleepTimerExpired) {
            stopProgressLoop();
            return;
        }

        if (playQueue.getIndex() < playQueue.size() - 1) {
            playQueue.offsetIndex(+1);
        } else if (!(currentMetadata instanceof OfflineMediaTag)
                && getPlayWhenReady() && !sleepTimerExpired && isAutoQueueEnabled()
                && getRepeatMode() == REPEAT_MODE_OFF) {
            requestNextRecommendation(false);
        }
        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Repeat and shuffle
    //////////////////////////////////////////////////////////////////////////*/
    //region Repeat and shuffle

    @RepeatMode
    public int getRepeatMode() {
        return exoPlayerIsNull() ? REPEAT_MODE_OFF : simpleExoPlayer.getRepeatMode();
    }

    public void cycleNextRepeatMode() {
        if (!exoPlayerIsNull()) {
            @RepeatMode final int repeatMode;
            switch (simpleExoPlayer.getRepeatMode()) {
                case REPEAT_MODE_OFF:
                    repeatMode = REPEAT_MODE_ONE;
                    break;
                case REPEAT_MODE_ONE:
                    repeatMode = REPEAT_MODE_ALL;
                    break;
                case REPEAT_MODE_ALL:
                default:
                    repeatMode = REPEAT_MODE_OFF;
                    break;
            }
            simpleExoPlayer.setRepeatMode(repeatMode);
        }
    }

    @Override
    public void onRepeatModeChanged(@RepeatMode final int repeatMode) {
        saveLastSession();
        recommendationRequest.set(null);
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onRepeatModeChanged() called with: "
                    + "repeatMode = [" + repeatMode + "]");
        }
        UIs.call(playerUi -> playerUi.onRepeatModeChanged(repeatMode));
        if (repeatMode == REPEAT_MODE_OFF) {
            getCurrentStreamInfo().ifPresent(this::maybeAutoQueueNextStream);
        }
        notifyPlaybackUpdateToListeners();
    }

    @Override
    public void onShuffleModeEnabledChanged(final boolean shuffleModeEnabled) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onShuffleModeEnabledChanged() called with: "
                    + "mode = [" + shuffleModeEnabled + "]");
        }

        if (playQueue != null) {
            if (shuffleModeEnabled) {
                playQueue.shuffle();
            } else {
                playQueue.unshuffle();
            }
        }

        UIs.call(playerUi -> playerUi.onShuffleModeEnabledChanged(shuffleModeEnabled));
        notifyPlaybackUpdateToListeners();
    }

    public void toggleShuffleModeEnabled() {
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.setShuffleModeEnabled(!simpleExoPlayer.getShuffleModeEnabled());
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Mute / Unmute
    //////////////////////////////////////////////////////////////////////////*/
    //region Mute / Unmute

    public void toggleMute() {
        final boolean wasMuted = isMuted();
        userMuted = !wasMuted;
        applySleepGain(sleepTimer.getGain());
        if (wasMuted) {
            audioReactor.requestAudioFocus();
        } else {
            audioReactor.abandonAudioFocus();
        }
        UIs.call(playerUi -> playerUi.onMuteUnmuteChanged(!wasMuted));
        notifyPlaybackUpdateToListeners();
    }

    public boolean isMuted() {
        return userMuted;
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // ExoPlayer listeners (that didn't fit in other categories)
    //////////////////////////////////////////////////////////////////////////*/
    //region ExoPlayer listeners (that didn't fit in other categories)

    /**
     * <p>Listens for event or state changes on ExoPlayer. When any event happens, we check for
     * changes in the currently-playing metadata and update the encapsulating
     * {@link Player}. Downstream listeners are also informed.</p>
     *
     * <p>When the renewed metadata contains any error, it is reported as a notification.
     * This is done because not all source resolution errors are {@link PlaybackException}, which
     * are also captured by {@link ExoPlayer} and stops the playback.</p>
     *
     * @param player The {@link com.google.android.exoplayer2.Player} whose state changed.
     * @param events The {@link com.google.android.exoplayer2.Player.Events} that has triggered
     *               the player state changes.
     **/
    @Override
    public void onEvents(@NonNull final com.google.android.exoplayer2.Player player,
                         @NonNull final com.google.android.exoplayer2.Player.Events events) {
        Listener.super.onEvents(player, events);
        MediaItemTag.from(player.getCurrentMediaItem()).ifPresent(tag -> {
            if (tag == currentMetadata) {
                return; // we still have the same metadata, no need to do anything
            }
            final StreamInfo previousInfo = Optional.ofNullable(currentMetadata)
                    .flatMap(MediaItemTag::getMaybeStreamInfo).orElse(null);
            final MediaItemTag.AudioTrack previousAudioTrack =
                    Optional.ofNullable(currentMetadata)
                            .flatMap(MediaItemTag::getMaybeAudioTrack).orElse(null);
            currentMetadata = tag;

            if (currentMetadata.getErrors().stream()
                    .anyMatch(AudioOnlyUnavailableException.class::isInstance)) {
                Toast.makeText(context, R.string.data_saver_audio_unavailable,
                        Toast.LENGTH_LONG).show();
            } else if (currentMetadata instanceof ExceptionTag
                    && (recoveryRefreshingSource || currentMetadata.getErrors().stream().anyMatch(
                            FailedMediaSource.FailedMediaSourceException.class::isInstance))) {
                // Extractor/access failures are delivered as a short silence source, not as
                // onPlayerError. Stop it before it can advance the queue or show a report UI.
                finishPlaybackRecovery();
                return;
            } else if (!currentMetadata.getErrors().isEmpty()) {
                Log.w(TAG, "Stream metadata contains reported errors");
            }

            currentMetadata.getMaybeStreamInfo().ifPresent(info -> {
                if (DEBUG) {
                    Log.d(TAG, "ExoPlayer - onEvents() update stream info: " + info.getName());
                }
                if (previousInfo == null || !previousInfo.getUrl().equals(info.getUrl())) {
                    // only update with the new stream info if it has actually changed
                    updateMetadataWith(info);
                } else if (previousAudioTrack == null
                        || tag.getMaybeAudioTrack()
                        .map(t -> t.getSelectedAudioStreamIndex()
                                != previousAudioTrack.getSelectedAudioStreamIndex())
                        .orElse(false)) {
                    notifyAudioTrackUpdateToListeners();
                }
            });
        });
    }

    @Override
    public void onTracksChanged(@NonNull final Tracks tracks) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onTracksChanged(), "
                    + "track group size = " + tracks.getGroups().size());
        }
        UIs.call(playerUi -> playerUi.onTextTracksChanged(tracks));
    }

    @Override
    public void onPlaybackParametersChanged(@NonNull final PlaybackParameters playbackParameters) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - playbackParameters(), speed = [" + playbackParameters.speed
                    + "], pitch = [" + playbackParameters.pitch + "]");
        }
        UIs.call(playerUi -> playerUi.onPlaybackParametersChanged(playbackParameters));
    }

    @Override
    public void onPositionDiscontinuity(@NonNull final PositionInfo oldPosition,
                                        @NonNull final PositionInfo newPosition,
                                        @DiscontinuityReason final int discontinuityReason) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onPositionDiscontinuity() called with "
                    + "oldPositionIndex = [" + oldPosition.mediaItemIndex + "], "
                    + "oldPositionMs = [" + oldPosition.positionMs + "], "
                    + "newPositionIndex = [" + newPosition.mediaItemIndex + "], "
                    + "newPositionMs = [" + newPosition.positionMs + "], "
                    + "discontinuityReason = [" + discontinuityReason + "]");
        }
        if (playQueue == null) {
            return;
        }

        // Refresh the playback if there is a transition to the next video
        final int newIndex = newPosition.mediaItemIndex;
        switch (discontinuityReason) {
            case DISCONTINUITY_REASON_AUTO_TRANSITION:
            case DISCONTINUITY_REASON_REMOVE:
                // When player is in single repeat mode and a period transition occurs,
                // we need to register a view count here since no metadata has changed
                if (getRepeatMode() == REPEAT_MODE_ONE && newIndex == playQueue.getIndex()) {
                    registerStreamViewed();
                    break;
                }
            case DISCONTINUITY_REASON_SEEK:
                if (DEBUG) {
                    Log.d(TAG, "ExoPlayer - onSeekProcessed() called");
                }
                if (isPrepared) {
                    saveStreamProgressState();
                }
            case DISCONTINUITY_REASON_SEEK_ADJUSTMENT:
            case DISCONTINUITY_REASON_INTERNAL:
                // Player index may be invalid when playback is blocked
                if (getCurrentState() != STATE_BLOCKED && newIndex != playQueue.getIndex()) {
                    saveStreamProgressStateCompleted(); // current stream has ended
                    playQueue.setIndex(newIndex);
                }
                break;
            case DISCONTINUITY_REASON_SKIP:
                break; // only makes Android Studio linter happy, as there are no ads
        }
        saveLastSession();
    }

    @Override
    public void onRenderedFirstFrame() {
        UIs.call(PlayerUi::onRenderedFirstFrame);
    }

    @Override
    public void onCues(@NonNull final CueGroup cueGroup) {
        UIs.call(playerUi -> playerUi.onCues(cueGroup.cues));
    }

    /**
     * To be called when the {@code PlaybackPreparer} set in the {@link MediaSessionConnector}
     * receives an {@code onPrepare()} call. This function allows restoring the default behavior
     * that would happen if there was no playback preparer set, i.e. to just call
     * {@code player.prepare()}. You can find the default behavior in `onPlay()` inside the
     * {@link MediaSessionConnector} file.
     */
    public void onPrepare() {
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.prepare();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Errors
    //////////////////////////////////////////////////////////////////////////*/
    //region Errors

    /**
     * Process exceptions produced by {@link com.google.android.exoplayer2.ExoPlayer ExoPlayer}.
     * Recoverable transport failures receive bounded retries; ambiguous rejected media URLs
     * receive at most one ordinary metadata refresh. Source failures retain the current queue
     * item and finish paused with a manual retry notice. Device/renderer failures retain their
     * existing shutdown and diagnostic handling. Behind-live-window errors seek to the live edge.
     *
     * @see com.google.android.exoplayer2.Player.Listener#onPlayerError(PlaybackException)
     */
    // Any error code not explicitly covered here are either unrelated to NewPipe use case
    // (e.g. DRM) or not recoverable (e.g. Decoder error). In both cases, the player should
    // shutdown.
    @SuppressWarnings("SwitchIntDef")
    @Override
    public void onPlayerError(@NonNull final PlaybackException error) {
        Log.e(TAG, "ExoPlayer - onPlayerError() called with:", error);

        saveStreamProgressState();
        boolean isCatchableException = false;

        switch (error.errorCode) {
            case ERROR_CODE_BEHIND_LIVE_WINDOW:
                isCatchableException = true;
                simpleExoPlayer.seekToDefaultPosition();
                simpleExoPlayer.prepare();
                // Inform the user that we are reloading the stream by
                // switching to the buffering state
                onBuffering();
                break;
            case ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE:
            case ERROR_CODE_IO_BAD_HTTP_STATUS:
            case ERROR_CODE_IO_FILE_NOT_FOUND:
            case ERROR_CODE_IO_NO_PERMISSION:
            case ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED:
            case ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE:
            case ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            case ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                recoverPlayback(error);
                isCatchableException = true;
                break;
            case ERROR_CODE_TIMEOUT:
            case ERROR_CODE_IO_UNSPECIFIED:
            case ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
            case ERROR_CODE_UNSPECIFIED:
                recoverPlayback(error);
                isCatchableException = true;
                break;
            default:
                // API, remote and renderer errors belong here:
                onPlaybackShutdown();
                break;
        }

        if (!isCatchableException) {
            createErrorNotification(error);
        }

        if (fragmentListener != null) {
            fragmentListener.onPlayerError(error, isCatchableException);
        }
    }

    private void cancelPlaybackRecovery() {
        recoveryRequest.set(null);
        playbackRecovery.cancel();
    }

    private void retireRecoverySourceManager() {
        if (recoveryReloading && !exoPlayerIsNull()) {
            simpleExoPlayer.stop();
        }
        if (recoveryReloading && playQueueManager != null) {
            // A canceled metadata request must not prepare or retry from a late result.
            playQueueManager.dispose();
            playQueueManager = null;
        }
        recoveryReloading = false;
        recoveryRefreshingSource = false;
    }

    private void recoverPlayback(final PlaybackException error) {
        if (destroyed || exoPlayerIsNull() || playQueue == null || playQueue.getItem() == null) {
            return;
        }
        if (recoveryFailed || recoveryCanceled) {
            return;
        }
        if (recoveryRefreshingSource
                && (currentMetadata == null || currentMetadata instanceof ExceptionTag)) {
            finishPlaybackRecovery();
            return;
        }
        final PlayQueue queue = playQueue;
        final PlayQueueItem item = queue.getItem();
        final boolean localOnly = currentMetadata instanceof OfflineMediaTag
                || item.getUrl().startsWith(OfflineStore.LOCAL_PREFIX);
        final PlaybackRecovery.Attempt attempt = playbackRecovery.next(item,
                simpleExoPlayer.getCurrentPosition(), getPlayWhenReady(),
                recoveryFailure(error), localOnly);
        if (attempt == null) {
            finishPlaybackRecovery();
            return;
        }
        // Do not call play() in an asynchronous callback: current intent, focus and timer
        // state remain authoritative throughout a source replacement.
        changeState(STATE_BUFFERING);
        recoveryRequest.set(Observable.timer(attempt.delayMillis, MILLISECONDS,
                        AndroidSchedulers.mainThread())
                .subscribe(ignored -> {
                    if (destroyed || exoPlayerIsNull() || playQueue != queue
                            || !playbackRecovery.isCurrent(attempt, queue.getItem())
                            || sleepTimer.expireIfDue() || sleepTimerExpired) {
                        return;
                    }
                    queue.setRecovery(queue.getIndex(), attempt.positionMillis);
                    if (attempt.refreshSource) {
                        InfoCache.getInstance().removeInfo(item.getServiceId(), item.getUrl(),
                                InfoCache.Type.STREAM);
                    }
                    recoveryReloading = true;
                    recoveryRefreshingSource = attempt.refreshSource;
                    reloadPlayQueueManager();
                }, ignored -> finishPlaybackRecovery()));
    }

    private static PlaybackRecovery.Failure recoveryFailure(final PlaybackException error) {
        // Known extraction failures include login, geo and bot challenges. They are not
        // transient transport failures and must never be retried as such.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.schabi.newpipe.extractor.exceptions.ExtractionException) {
                return PlaybackRecovery.Failure.PERMANENT;
            }
        }
        if (error.errorCode == ERROR_CODE_IO_BAD_HTTP_STATUS) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                    return PlaybackRecovery.classifyHttpStatus(
                            ((HttpDataSource.InvalidResponseCodeException) cause).responseCode);
                }
            }
            return PlaybackRecovery.Failure.PERMANENT;
        }
        switch (error.errorCode) {
            case ERROR_CODE_TIMEOUT:
            case ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return PlaybackRecovery.Failure.TEMPORARY;
            case ERROR_CODE_IO_UNSPECIFIED:
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof java.net.SocketException
                            || cause instanceof java.net.SocketTimeoutException
                            || cause instanceof java.net.UnknownHostException) {
                        return PlaybackRecovery.Failure.TEMPORARY;
                    }
                }
                return PlaybackRecovery.Failure.PERMANENT;
            default:
                return PlaybackRecovery.Failure.PERMANENT;
        }
    }

    private void finishPlaybackRecovery() {
        cancelPlaybackRecovery();
        if (exoPlayerIsNull()) {
            return;
        }
        pause();
        simpleExoPlayer.stop();
        changeState(STATE_PAUSED);
        if (!recoveryFailed) {
            recoveryFailed = true;
            Toast.makeText(context, R.string.playback_recovery_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void createErrorNotification(@NonNull final PlaybackException error) {
        final ErrorInfo errorInfo;
        if (currentMetadata == null) {
            errorInfo = new ErrorInfo(error, UserAction.PLAY_STREAM,
                    "Player error[type=" + error.getErrorCodeName()
                            + "] occurred, currentMetadata is null");
        } else {
            errorInfo = new ErrorInfo(error, UserAction.PLAY_STREAM,
                    "Player error[type=" + error.getErrorCodeName()
                            + "] occurred while playing " + currentMetadata.getStreamUrl(),
                    currentMetadata.getServiceId(), currentMetadata.getStreamUrl());
        }
        ErrorUtil.createNotification(context, errorInfo);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback position and seek
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback position and seek

    @Override // own playback listener (this is a getter)
    public boolean isApproachingPlaybackEdge(final long timeToEndMillis) {
        // If live, then not near playback edge
        // If not playing, then not approaching playback edge
        if (exoPlayerIsNull() || isLive() || !isPlaying()) {
            return false;
        }

        final long currentPositionMillis = simpleExoPlayer.getCurrentPosition();
        final long currentDurationMillis = simpleExoPlayer.getDuration();
        return currentDurationMillis - currentPositionMillis < timeToEndMillis;
    }

    /**
     * Checks if the current playback is a livestream AND is playing at or beyond the live edge.
     *
     * @return whether the livestream is playing at or beyond the edge
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isLiveEdge() {
        if (exoPlayerIsNull() || !isLive()) {
            return false;
        }

        final Timeline currentTimeline = simpleExoPlayer.getCurrentTimeline();
        final int currentWindowIndex = simpleExoPlayer.getCurrentMediaItemIndex();
        if (currentTimeline.isEmpty() || currentWindowIndex < 0
                || currentWindowIndex >= currentTimeline.getWindowCount()) {
            return false;
        }

        final Timeline.Window timelineWindow = new Timeline.Window();
        currentTimeline.getWindow(currentWindowIndex, timelineWindow);
        return timelineWindow.getDefaultPositionMs() <= simpleExoPlayer.getCurrentPosition();
    }

    @Override // own playback listener
    public void onPlaybackSynchronize(@NonNull final PlayQueueItem item, final boolean wasBlocked) {
        if (DEBUG) {
            Log.d(TAG, "Playback - onPlaybackSynchronize(was blocked: " + wasBlocked
                    + ") called with item=[" + item.getTitle() + "], url=[" + item.getUrl() + "]");
        }
        if (exoPlayerIsNull() || playQueue == null || currentItem == item) {
            return; // nothing to synchronize
        }

        final int playQueueIndex = playQueue.indexOf(item);
        final int playlistIndex = simpleExoPlayer.getCurrentMediaItemIndex();
        final int playlistSize = simpleExoPlayer.getCurrentTimeline().getWindowCount();
        final boolean removeThumbnailBeforeSync = currentItem == null
                || currentItem.getServiceId() != item.getServiceId()
                || !currentItem.getUrl().equals(item.getUrl());
        final boolean switchingItem = currentItem != null && currentItem != item;

        currentItem = item;
        if (switchingItem) {
            recoveryFailed = false;
        }

        if (playQueueIndex != playQueue.getIndex()) {
            // wrong window (this should be impossible, as this method is called with
            // `item=playQueue.getItem()`, so the index of that item must be equal to `getIndex()`)
            Log.e(TAG, "Playback - Play Queue may be not in sync: item index=["
                    + playQueueIndex + "], " + "queue index=[" + playQueue.getIndex() + "]");

        } else if ((playlistSize > 0 && playQueueIndex >= playlistSize) || playQueueIndex < 0) {
            // the queue and the player's timeline are not in sync, since the play queue index
            // points outside of the timeline
            Log.e(TAG, "Playback - Trying to seek to invalid index=[" + playQueueIndex
                    + "] with playlist length=[" + playlistSize + "]");

        } else if (wasBlocked || playlistIndex != playQueueIndex || !isPlaying()) {
            // either the player needs to be unblocked, or the play queue index has just been
            // changed and needs to be synchronized, or the player is not playing
            if (DEBUG) {
                Log.d(TAG, "Playback - Rewinding to correct index=[" + playQueueIndex + "], "
                        + "from=[" + playlistIndex + "], size=[" + playlistSize + "].");
            }

            if (removeThumbnailBeforeSync) {
                // unset the current (now outdated) thumbnail to ensure it is not used during sync
                onThumbnailLoaded(null);
            }

            // sync the player index with the queue index, and seek to the correct position
            if (item.getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET) {
                simpleExoPlayer.seekTo(playQueueIndex, item.getRecoveryPosition());
                playQueue.unsetRecovery(playQueueIndex);
            } else {
                simpleExoPlayer.seekToDefaultPosition(playQueueIndex);
            }
            // Selecting another item after an error only seeks the already loaded playlist.
            // A stopped ExoPlayer also needs prepare(), retaining its current play intent.
            if (switchingItem && !recoveryFailed && !recoveryCanceled && isStopped()) {
                simpleExoPlayer.prepare();
            }
        }
    }

    public void seekTo(final long positionMillis) {
        if (DEBUG) {
            Log.d(TAG, "seekBy() called with: position = [" + positionMillis + "]");
        }
        if (!exoPlayerIsNull()) {
            // prevent invalid positions when fast-forwarding/-rewinding
            simpleExoPlayer.seekTo(MathUtils.clamp(positionMillis, 0,
                    simpleExoPlayer.getDuration()));
        }
    }

    private void seekBy(final long offsetMillis) {
        if (DEBUG) {
            Log.d(TAG, "seekBy() called with: offsetMillis = [" + offsetMillis + "]");
        }
        seekTo(simpleExoPlayer.getCurrentPosition() + offsetMillis);
    }

    public void seekToDefault() {
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.seekToDefaultPosition();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Player actions (play, pause, previous, fast-forward, ...)
    //////////////////////////////////////////////////////////////////////////*/
    //region Player actions (play, pause, previous, fast-forward, ...)

    public void play() {
        if (DEBUG) {
            Log.d(TAG, "play() called");
        }
        if (audioReactor == null || playQueue == null || exoPlayerIsNull()) {
            return;
        }

        sleepTimer.expireIfDue();
        sleepTimerExpired = false;

        if (!isMuted() && !audioReactor.requestAudioFocus()) {
            return;
        }

        final long retryPosition = playbackRecovery.positionFor(playQueue.getItem(),
                simpleExoPlayer.getCurrentPosition());
        cancelPlaybackRecovery();
        playbackRecovery.reset();
        recoveryFailed = false;
        recoveryCanceled = false;
        recoveryRefreshingSource = false;

        if (currentState == STATE_COMPLETED) {
            if (playQueue.getIndex() == 0) {
                seekToDefault();
            } else {
                playQueue.setIndex(0);
            }
        }

        if (isStopped()) {
            playQueue.setRecovery(playQueue.getIndex(), retryPosition);
            reloadPlayQueueManager();
        }

        simpleExoPlayer.play();
        saveStreamProgressState();
    }

    public void setSleepTimer(final long durationMillis) {
        sleepTimer.start(durationMillis);
        applySleepTimerMode();
    }

    public void cancelSleepTimer() {
        sleepTimer.cancel();
        applySleepTimerMode();
    }

    public void setSleepTimerAtEndOfItem() {
        if (canSetSleepTimerAtEndOfItem()) {
            sleepTimer.startAtEndOfItem();
            applySleepTimerMode();
            triggerProgressUpdate();
        }
    }

    public boolean canSetSleepTimerAtEndOfItem() {
        return !exoPlayerIsNull() && !isLive() && playQueue != null && playQueue.getItem() != null
                && !StreamTypeUtil.isLiveStream(playQueue.getItem().getStreamType());
    }

    public boolean isSleepTimerAtEndOfItem() {
        return sleepTimer.isEndOfItem();
    }

    public void extendSleepTimer() {
        sleepTimer.extend(15 * 60_000L);
        applySleepTimerMode();
    }

    public void setSleepTimerFadeEnabled(final boolean enabled) {
        prefs.edit().putBoolean(SLEEP_FADE_KEY, enabled).apply();
        sleepTimer.setFadeEnabled(enabled);
        triggerProgressUpdate();
    }

    public boolean isSleepTimerFadeEnabled() {
        return sleepTimer.isFadeEnabled();
    }

    private void applySleepTimerMode() {
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.setPauseAtEndOfMediaItems(sleepTimer.isEndOfItem());
        }
    }

    private void applySleepGain(final double gain) {
        if (audioReactor != null) {
            audioReactor.setPlaybackGain(userMuted ? 0 : (float) gain);
        }
    }

    public void restartCurrent() {
        seekToDefault();
        triggerProgressUpdate();
    }

    public long getSleepTimerRemainingMillis() {
        sleepTimer.expireIfDue();
        return sleepTimer.remainingMillis();
    }

    public boolean isAutoQueueEnabled() {
        return PlayerHelper.isAutoQueueEnabled(context);
    }

    public void setAutoQueueEnabled(final boolean enabled) {
        if (!enabled) {
            recommendationRequest.set(null);
        }
        prefs.edit().putBoolean(context.getString(R.string.auto_queue_key), enabled).apply();
        if (enabled) {
            getCurrentStreamInfo().ifPresent(this::maybeAutoQueueNextStream);
        }
    }

    public void pause() {
        recoveryCanceled |= recoveryReloading || recoveryRequest.get() != null;
        cancelPlaybackRecovery();
        retireRecoverySourceManager();
        recommendationRequest.set(null);
        if (DEBUG) {
            Log.d(TAG, "pause() called");
        }
        if (audioReactor == null || exoPlayerIsNull()) {
            return;
        }

        audioReactor.abandonAudioFocus();
        simpleExoPlayer.pause();
        if (isStopped()) {
            changeState(STATE_PAUSED);
        }
        saveStreamProgressState();
    }

    /** Stop requested by the media session, preserving ExoPlayer's stop semantics. */
    public void stop() {
        recoveryCanceled = true;
        cancelPlaybackRecovery();
        retireRecoverySourceManager();
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.stop();
        }
    }

    public void playPause() {
        if (DEBUG) {
            Log.d(TAG, "onPlayPause() called");
        }

        if (getPlayWhenReady()
                // When state is completed (replay button is shown) then (re)play and do not pause
                && currentState != STATE_COMPLETED) {
            pause();
        } else {
            play();
        }
    }

    public void playPrevious() {
        cancelPlaybackRecovery();
        playbackRecovery.reset();
        recoveryFailed = false;
        recoveryCanceled = false;
        recoveryRefreshingSource = false;
        recommendationRequest.set(null);
        if (DEBUG) {
            Log.d(TAG, "onPlayPrevious() called");
        }
        if (exoPlayerIsNull() || playQueue == null) {
            return;
        }

        if (sleepTimer.isEndOfItem()) {
            cancelSleepTimer();
        }
        if (playQueue.getIndex() == 0) {
            seekToDefault();
            playQueue.offsetIndex(0);
        } else {
            saveStreamProgressState();
            playQueue.offsetIndex(-1);
        }
        triggerProgressUpdate();
    }

    public void playNext() {
        cancelPlaybackRecovery();
        playbackRecovery.reset();
        recoveryFailed = false;
        recoveryCanceled = false;
        recoveryRefreshingSource = false;
        if (sleepTimer.isEndOfItem()) {
            cancelSleepTimer();
        }
        if (DEBUG) {
            Log.d(TAG, "onPlayNext() called");
        }
        if (playQueue == null) {
            return;
        }

        saveStreamProgressState();
        if (playQueue.getIndex() < playQueue.size() - 1
                || (playQueue.isComplete() && getRepeatMode() == REPEAT_MODE_ALL)) {
            recommendationRequest.set(null);
            playQueue.offsetIndex(+1);
        } else {
            requestNextRecommendation(true);
        }
        triggerProgressUpdate();
    }

    public void fastForward() {
        if (DEBUG) {
            Log.d(TAG, "fastRewind() called");
        }
        seekBy(retrieveSeekDurationFromPreferences(this));
        triggerProgressUpdate();
    }

    public void fastRewind() {
        if (DEBUG) {
            Log.d(TAG, "fastRewind() called");
        }
        seekBy(-retrieveSeekDurationFromPreferences(this));
        triggerProgressUpdate();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // StreamInfo history: views and progress
    //////////////////////////////////////////////////////////////////////////*/
    //region StreamInfo history: views and progress

    private void registerStreamViewed() {
        getCurrentStreamInfo().ifPresent(info -> databaseUpdateDisposable
                .add(recordManager.onViewed(info).onErrorComplete().subscribe()));
    }

    private void saveStreamProgressState(final long progressMillis) {
        getCurrentStreamInfo().ifPresent(info -> {
            if (!prefs.getBoolean(context.getString(R.string.enable_watch_history_key), true)) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "saveStreamProgressState() called with: progressMillis=" + progressMillis
                        + ", currentMetadata=[" + info.getName() + "]");
            }

            databaseUpdateDisposable.add(recordManager.saveStreamState(info, progressMillis)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnError(e -> {
                        if (DEBUG) {
                            e.printStackTrace();
                        }
                    })
                    .onErrorComplete()
                    .subscribe());
        });
    }

    public void saveStreamProgressState() {
        if (exoPlayerIsNull() || currentMetadata == null || playQueue == null
                || playQueue.getIndex() != simpleExoPlayer.getCurrentMediaItemIndex()) {
            // Make sure play queue and current window index are equal, to prevent saving state for
            // the wrong stream on discontinuity (e.g. when the stream just changed but the
            // playQueue index and currentMetadata still haven't updated)
            return;
        }
        // Save current position. It will help to restore this position once a user
        // wants to play prev or next stream from the queue
        playQueue.setRecovery(playQueue.getIndex(), simpleExoPlayer.getContentPosition());
        saveStreamProgressState(simpleExoPlayer.getCurrentPosition());
    }

    public void saveStreamProgressStateCompleted() {
        // current stream has ended, so the progress is its duration (+1 to overcome rounding)
        getCurrentStreamInfo().ifPresent(info ->
                saveStreamProgressState((info.getDuration() + 1) * 1000));
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Metadata
    //////////////////////////////////////////////////////////////////////////*/
    //region Metadata

    private void updateMetadataWith(@NonNull final StreamInfo info) {
        if (DEBUG) {
            Log.d(TAG, "Playback - onMetadataChanged() called, playing: " + info.getName());
        }
        if (exoPlayerIsNull()) {
            return;
        }

        if (!(currentMetadata instanceof OfflineMediaTag)) {
            maybeAutoQueueNextStream(info);
        }

        loadCurrentThumbnail(info.getThumbnails());
        registerStreamViewed();

        notifyMetadataUpdateToListeners();
        notifyAudioTrackUpdateToListeners();
        UIs.call(playerUi -> playerUi.onMetadataChanged(info));
    }

    @NonNull
    public String getVideoUrl() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getStreamUrl();
    }

    @NonNull
    public String getVideoUrlAtCurrentTime() {
        final long timeSeconds = simpleExoPlayer.getCurrentPosition() / 1000;
        String videoUrl = getVideoUrl();
        if (!isLive() && timeSeconds >= 0 && currentMetadata != null
                && currentMetadata.getServiceId() == YouTube.getServiceId()) {
            // Timestamp doesn't make sense in a live stream so drop it
            videoUrl += ("&t=" + timeSeconds);
        }
        return videoUrl;
    }

    @NonNull
    public String getVideoTitle() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getTitle();
    }

    @NonNull
    public String getUploaderName() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getUploaderName();
    }

    @Nullable
    public Bitmap getThumbnail() {
        return currentThumbnail;
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Play queue, segments and streams
    //////////////////////////////////////////////////////////////////////////*/
    //region Play queue, segments and streams

    /** Save a stable queue snapshot without keeping temporary media URLs or a running timer. */
    public void saveLastSession() {
        if (playQueue == null) {
            return;
        }
        final PlayQueueItem item = playQueue.getItem();
        long position = item == null ? 0 : Math.max(0, item.getRecoveryPosition());
        if (item != null && item.getRecoveryPosition() == PlayQueueItem.RECOVERY_UNSET
                && !exoPlayerIsNull()
                && simpleExoPlayer.getCurrentMediaItemIndex() == playQueue.getIndex()
                && MediaItemTag.from(simpleExoPlayer.getCurrentMediaItem())
                .filter(tag -> tag.getServiceId() == item.getServiceId()
                        && tag.getStreamUrl().equals(item.getUrl())).isPresent()) {
            position = Math.max(0, simpleExoPlayer.getContentPosition());
        }
        lastSessionStore.save(playQueue, position, getRepeatMode());
        lastSessionSave = SystemClock.elapsedRealtime();
    }

    private boolean recommendationAllowed(final PlayQueueItem item) {
        return recommendationAllowed(item.getServiceId(), item.getUrl(), item.getUploaderUrl());
    }

    private boolean recommendationAllowed(final int serviceId, final String url,
                                           @Nullable final String uploaderUrl) {
        return !recommendationExclusions.excludes(serviceId, url, uploaderUrl);
    }

    public boolean excludeNextRecommendation(final boolean channel) {
        final PlayQueueItem item = getNextRecommendation();
        if (item == null || !canReplaceNextRecommendation()) {
            return false;
        }
        final boolean added = channel ? recommendationExclusions.excludeChannel(item)
                : recommendationExclusions.excludeVideo(item);
        if (!added) {
            return false;
        }
        recommendationRequest.set(null);
        recommendationPreview = null;
        final int nextIndex = playQueue.getIndex() + 1;
        if (playQueue.getItem(nextIndex) == item) {
            playQueue.remove(nextIndex);
        }
        recommendationStatus = RecommendationStatus.IDLE;
        loadNextRecommendation();
        triggerProgressUpdate();
        return true;
    }

    public void undoRecommendationExclusion(final PlayQueueItem item, final boolean channel) {
        if (channel) {
            recommendationExclusions.allowChannel(item);
        } else {
            recommendationExclusions.allowVideo(item);
        }
        skippedRecommendations.clear();
        refreshRecommendationAfterUnblocking();
    }

    private void refreshRecommendationAfterUnblocking() {
        if (!canReplaceNextRecommendation()) {
            return;
        }
        recommendationRequest.set(null);
        recommendationPreview = null;
        if (playQueue.getItem(playQueue.getIndex() + 1) != null) {
            playQueue.remove(playQueue.getIndex() + 1);
        }
        recommendationStatus = RecommendationStatus.IDLE;
        loadNextRecommendation();
    }

    public boolean hasRecommendationExclusions() {
        return recommendationExclusions.hasExclusions();
    }

    public void clearRecommendationExclusions() {
        recommendationExclusions.clear();
        skippedRecommendations.clear();
        refreshRecommendationAfterUnblocking();
    }

    private void syncRecommendationContext() {
        final PlayQueueItem item = playQueue == null ? null : playQueue.getItem();
        if (recommendationAnchor != item) {
            recommendationRequest.set(null);
            recommendationAnchor = item;
            recommendationPreview = null;
            skippedRecommendations.clear();
            recommendationStatus = RecommendationStatus.IDLE;
        }
        if (recommendationPreview != null && !recommendationAllowed(recommendationPreview)) {
            recommendationPreview = null;
        }
    }

    @Nullable
    public PlayQueueItem getNextRecommendation() {
        syncRecommendationContext();
        final PlayQueueItem next = playQueue == null ? null
                : playQueue.getItem(playQueue.getIndex() + 1);
        return next == null ? recommendationPreview : next;
    }

    public List<InfoItem> getRelatedItemsForPlayback(@NonNull final StreamInfo info) {
        syncRecommendationContext();
        if (playQueue == null || playQueue.getItem() == null
                || playQueue.getItem().getServiceId() != info.getServiceId()
                || !playQueue.getItem().getUrl().equals(info.getUrl())) {
            return new ArrayList<>(info.getRelatedItems());
        }
        final Set<String> queued = new HashSet<>();
        for (final PlayQueueItem item : playQueue.getStreams()) {
            queued.add(item.getServiceId() + ":" + item.getUrl());
        }
        for (final PlayQueueItem item : skippedRecommendations) {
            queued.add(item.getServiceId() + ":" + item.getUrl());
        }
        final List<InfoItem> result = new ArrayList<>();
        final Set<String> added = new HashSet<>();
        final PlayQueueItem next = getNextRecommendation();
        if (next != null && (!next.isAutoQueued() || recommendationAllowed(next))) {
            final StreamInfoItem first = new StreamInfoItem(next.getServiceId(), next.getUrl(),
                    next.getTitle(), next.getStreamType());
            first.setDuration(next.getDuration());
            first.setUploaderName(next.getUploader());
            first.setUploaderUrl(next.getUploaderUrl());
            first.setThumbnails(next.getThumbnails());
            result.add(first);
            added.add(next.getServiceId() + ":" + next.getUrl());
        }
        for (final InfoItem item : info.getRelatedItems()) {
            final String key = item.getServiceId() + ":" + item.getUrl();
            if (item instanceof StreamInfoItem && !item.getUrl().isEmpty()
                    && !queued.contains(key) && recommendationAllowed(item.getServiceId(),
                    item.getUrl(), ((StreamInfoItem) item).getUploaderUrl()) && added.add(key)) {
                result.add(item);
            }
        }
        return result;
    }

    public RecommendationStatus getRecommendationStatus() {
        syncRecommendationContext();
        if (recommendationStatus == RecommendationStatus.LOADING
                && recommendationRequest.get() == null) {
            recommendationStatus = RecommendationStatus.IDLE;
        }
        if (recommendationStatus == RecommendationStatus.IDLE && getNextRecommendation() != null) {
            return RecommendationStatus.READY;
        }
        return recommendationStatus;
    }

    public boolean canReplaceNextRecommendation() {
        syncRecommendationContext();
        if (playQueue == null || playQueue.getItem() == null) {
            return false;
        }
        final PlayQueueItem next = playQueue.getItem(playQueue.getIndex() + 1);
        return next == null || (next.isAutoQueued()
                && playQueue.getIndex() + 1 == playQueue.size() - 1);
    }

    public void loadNextRecommendation() {
        requestRecommendation(RecommendationAction.PREVIEW);
    }

    public void replaceNextRecommendation() {
        requestRecommendation(RecommendationAction.REPLACE);
    }

    private void requestNextRecommendation(final boolean explicit) {
        requestRecommendation(explicit ? RecommendationAction.NEXT
                : RecommendationAction.AUTO_NEXT);
    }

    @SuppressWarnings("MethodLength")
    private void requestRecommendation(final RecommendationAction action) {
        if (currentMetadata instanceof OfflineMediaTag) {
            recommendationStatus = RecommendationStatus.EMPTY;
            triggerProgressUpdate();
            return;
        }
        syncRecommendationContext();
        final boolean advance = action == RecommendationAction.NEXT
                || action == RecommendationAction.AUTO_NEXT;
        final boolean explicit = action != RecommendationAction.AUTO_NEXT;
        final boolean replace = action == RecommendationAction.REPLACE;
        final PlayQueue queue = playQueue;
        if (advance && !recommendationAdvancing) {
            // A next command takes precedence over a pending preview, including at natural end.
            recommendationRequest.set(null);
        }
        if (queue == null || queue.getItem() == null || (advance && sleepTimerExpired)
                || recommendationRequest.get() != null) {
            return;
        }
        if ((!advance && !replace && getNextRecommendation() != null)
                || (replace && !canReplaceNextRecommendation())) {
            recommendationStatus = RecommendationStatus.READY;
            return;
        }
        final PlayQueueItem item = queue.getItem();
        recommendationAdvancing = advance;
        recommendationStatus = RecommendationStatus.LOADING;
        // A paged playlist owns its next items until its final page has been loaded.
        if (!queue.isComplete()) {
            if (queue.getBroadcastReceiver() == null) {
                return;
            }
            recommendationRequest.set(queue.getBroadcastReceiver()
                    .filter(event -> queue.isComplete() || queue.getIndex() < queue.size() - 1)
                    .take(1)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(event -> {
                        recommendationRequest.set(null);
                        if (playQueue == queue && queue.getItem() == item && !sleepTimerExpired) {
                            if (queue.getIndex() < queue.size() - 1) {
                                recommendationStatus = RecommendationStatus.READY;
                                if (advance) {
                                    queue.offsetIndex(+1);
                                }
                            } else {
                                requestRecommendation(action);
                            }
                        }
                    }, error -> {
                        recommendationRequest.set(null);
                        recommendationStatus = RecommendationStatus.ERROR;
                        Log.w(TAG, "Could not load the next playlist page", error);
                    }));
            queue.fetch();
            return;
        }
        final Single<StreamInfo> info = getCurrentStreamInfo()
                .filter(current -> current.getUrl().equals(item.getUrl()))
                .map(Single::just).orElseGet(item::getStream);
        recommendationRequest.set(info.observeOn(AndroidSchedulers.mainThread())
                .subscribe(current -> {
                    recommendationRequest.set(null);
                    if (playQueue != queue || queue.getItem() != item
                            || (advance && sleepTimerExpired)
                            || (!explicit && (!isAutoQueueEnabled() || !getPlayWhenReady()
                            || getRepeatMode() != REPEAT_MODE_OFF))) {
                        recommendationStatus = RecommendationStatus.IDLE;
                        return;
                    }
                    if (queue.getIndex() == queue.size() - 1 || replace) {
                        final PlayQueueItem previous = getNextRecommendation();
                        final List<PlayQueueItem> excluded = new ArrayList<>(queue.getStreams());
                        excluded.addAll(skippedRecommendations);
                        if (replace && previous != null) {
                            excluded.add(previous);
                        }
                        final PlayQueue next = advance && recommendationPreview != null
                                ? new SinglePlayQueue(recommendationPreview)
                                : PlayerHelper.autoQueueOf(current, excluded,
                                        candidate -> recommendationAllowed(
                                                candidate.getServiceId(), candidate.getUrl(),
                                                candidate.getUploaderUrl()));
                        if (next == null) {
                            recommendationStatus = RecommendationStatus.EMPTY;
                            triggerProgressUpdate();
                            if (explicit) {
                                Toast.makeText(context, R.string.personal_no_recommendation,
                                        Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        if (replace && !canReplaceNextRecommendation()) {
                            recommendationStatus = RecommendationStatus.READY;
                            return;
                        }
                        if (replace && previous != null) {
                            skippedRecommendations.add(previous);
                        }
                        if (replace && queue.getItem(queue.getIndex() + 1) != null) {
                            queue.remove(queue.getIndex() + 1);
                            queue.append(next.getStreams());
                            recommendationPreview = null;
                        } else if (advance) {
                            queue.append(next.getStreams());
                            recommendationPreview = null;
                        } else {
                            recommendationPreview = next.getItem();
                        }
                    }
                    recommendationStatus = RecommendationStatus.READY;
                    if (advance) {
                        queue.offsetIndex(+1);
                    }
                    triggerProgressUpdate();
                }, error -> {
                    recommendationRequest.set(null);
                    recommendationStatus = RecommendationStatus.ERROR;
                    Log.w(TAG, "Could not load the next recommendation", error);
                    if (explicit && playQueue == queue && queue.getItem() == item) {
                        Toast.makeText(context, R.string.personal_recommendation_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    private void maybeAutoQueueNextStream(@NonNull final StreamInfo info) {
        syncRecommendationContext();
        if (playQueue == null || playQueue.getIndex() != playQueue.size() - 1
                || !playQueue.isComplete() || playQueue.getItem() == null
                || !playQueue.getItem().getUrl().equals(info.getUrl())
                || getRepeatMode() != REPEAT_MODE_OFF
                || !PlayerHelper.isAutoQueueEnabled(context)) {
            return;
        }
        // auto queue when starting playback on the last item when not repeating
        final List<PlayQueueItem> excluded = new ArrayList<>(playQueue.getStreams());
        excluded.addAll(skippedRecommendations);
        final PlayQueue autoQueue = recommendationPreview != null
                ? new SinglePlayQueue(recommendationPreview)
                : PlayerHelper.autoQueueOf(info, excluded,
                        candidate -> recommendationAllowed(candidate.getServiceId(),
                                candidate.getUrl(), candidate.getUploaderUrl()));
        if (autoQueue != null) {
            playQueue.append(autoQueue.getStreams());
            recommendationPreview = null;
            recommendationStatus = RecommendationStatus.READY;
        }
    }

    public void selectQueueItem(final PlayQueueItem item) {
        cancelPlaybackRecovery();
        playbackRecovery.reset();
        recoveryFailed = false;
        recoveryCanceled = false;
        recoveryRefreshingSource = false;
        if (sleepTimer.isEndOfItem()) {
            cancelSleepTimer();
        }
        recommendationRequest.set(null);
        if (playQueue == null || exoPlayerIsNull()) {
            return;
        }

        final int index = playQueue.indexOf(item);
        if (index == -1) {
            return;
        }

        if (playQueue.getIndex() == index && simpleExoPlayer.getCurrentMediaItemIndex() == index) {
            seekToDefault();
        } else {
            saveStreamProgressState();
        }
        playQueue.setIndex(index);
    }

    @Override
    public void onPlayQueueEdited() {
        notifyPlaybackUpdateToListeners();
        UIs.call(PlayerUi::onPlayQueueEdited);
    }

    @Override
    @Nullable
    public MediaSource localSourceOf(final PlayQueueItem item) throws java.io.IOException {
        return OfflineLibrary.get(context).source(item);
    }

    @Override // own playback listener
    @Nullable
    public MediaSource sourceOf(final PlayQueueItem item, final StreamInfo info) {
        if (audioPlayerSelected() || (isAudioOnly && (DataSaver.isEnabled(context)
                || videoResolver.getStreamSourceType().orElse(
                SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY)
                == SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY))) {
            final MediaSource audioSource = audioResolver.resolve(info);
            if (audioSource == null && DataSaver.isEnabled(context)) {
                return FailedMediaSource.of(item, new AudioOnlyUnavailableException(
                        context.getString(R.string.data_saver_audio_unavailable)));
            }
            return audioSource;
        }

        // Even if the stream is played in background, we need to use the video resolver if the
        // info played is separated video-only and audio-only streams; otherwise, if the audio
        // resolver was called when the app was in background, the app will only stream audio when
        // the user come back to the app and will never fetch the video stream.
        // Note that the video is not fetched when the app is in background because the video
        // renderer is fully disabled (see useVideoAndSubtitles method), except for HLS streams
        // (see https://github.com/google/ExoPlayer/issues/9282).
        return videoResolver.resolve(info);
    }

    public void disablePreloadingOfCurrentTrack() {
        loadController.disablePreloadingOfCurrentTrack();
    }

    public Optional<VideoStream> getSelectedVideoStream() {
        return Optional.ofNullable(currentMetadata)
                .flatMap(MediaItemTag::getMaybeQuality)
                .filter(quality -> {
                    final int selectedStreamIndex = quality.getSelectedVideoStreamIndex();
                    return selectedStreamIndex >= 0
                            && selectedStreamIndex < quality.getSortedVideoStreams().size();
                })
                .map(quality -> quality.getSortedVideoStreams()
                        .get(quality.getSelectedVideoStreamIndex()));
    }

    public Optional<AudioStream> getSelectedAudioStream() {
        return Optional.ofNullable(currentMetadata)
                .flatMap(MediaItemTag::getMaybeAudioTrack)
                .map(MediaItemTag.AudioTrack::getSelectedAudioStream);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Captions (text tracks)
    //////////////////////////////////////////////////////////////////////////*/
    //region Captions (text tracks)

    public int getCaptionRendererIndex() {
        if (exoPlayerIsNull()) {
            return RENDERER_UNAVAILABLE;
        }

        for (int t = 0; t < simpleExoPlayer.getRendererCount(); t++) {
            if (simpleExoPlayer.getRendererType(t) == C.TRACK_TYPE_TEXT) {
                return t;
            }
        }

        return RENDERER_UNAVAILABLE;
    }
    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Video size
    //////////////////////////////////////////////////////////////////////////*/
    //region Video size
    @Override // exoplayer listener
    public void onVideoSizeChanged(@NonNull final VideoSize videoSize) {
        if (DEBUG) {
            Log.d(TAG, "onVideoSizeChanged() called with: "
                    + "width / height = [" + videoSize.width + " / " + videoSize.height
                    + " = " + (((float) videoSize.width) / videoSize.height) + "], "
                    + "unappliedRotationDegrees = [" + videoSize.unappliedRotationDegrees + "], "
                    + "pixelWidthHeightRatio = [" + videoSize.pixelWidthHeightRatio + "]");
        }

        UIs.call(playerUi -> playerUi.onVideoSizeChanged(videoSize));
    }
    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Activity / fragment binding
    //////////////////////////////////////////////////////////////////////////*/
    //region Activity / fragment binding

    public void setFragmentListener(final PlayerServiceEventListener listener) {
        fragmentListener = listener;
        UIs.call(PlayerUi::onFragmentListenerSet);
        notifyQueueUpdateToListeners();
        notifyMetadataUpdateToListeners();
        notifyPlaybackUpdateToListeners();
        triggerProgressUpdate();
    }

    public void removeFragmentListener(final PlayerServiceEventListener listener) {
        if (fragmentListener == listener) {
            fragmentListener = null;
        }
    }

    void setActivityListener(final PlayerEventListener listener) {
        activityListener = listener;
        // TODO why not queue update?
        notifyMetadataUpdateToListeners();
        notifyPlaybackUpdateToListeners();
        triggerProgressUpdate();
    }

    void removeActivityListener(final PlayerEventListener listener) {
        if (activityListener == listener) {
            activityListener = null;
        }
    }

    void stopActivityBinding() {
        if (fragmentListener != null) {
            fragmentListener.onServiceStopped();
            fragmentListener = null;
        }
        if (activityListener != null) {
            activityListener.onServiceStopped();
            activityListener = null;
        }
    }

    private void notifyQueueUpdateToListeners() {
        if (fragmentListener != null && playQueue != null) {
            fragmentListener.onQueueUpdate(playQueue);
        }
        if (activityListener != null && playQueue != null) {
            activityListener.onQueueUpdate(playQueue);
        }
    }

    private void notifyMetadataUpdateToListeners() {
        getCurrentStreamInfo().ifPresent(info -> {
            if (fragmentListener != null) {
                fragmentListener.onMetadataUpdate(info, playQueue);
            }
            if (activityListener != null) {
                activityListener.onMetadataUpdate(info, playQueue);
            }
        });
    }

    private void notifyPlaybackUpdateToListeners() {
        if (fragmentListener != null && !exoPlayerIsNull() && playQueue != null) {
            fragmentListener.onPlaybackUpdate(currentState, getRepeatMode(),
                    playQueue.isShuffled(), simpleExoPlayer.getPlaybackParameters());
        }
        if (activityListener != null && !exoPlayerIsNull() && playQueue != null) {
            activityListener.onPlaybackUpdate(currentState, getRepeatMode(),
                    playQueue.isShuffled(), getPlaybackParameters());
        }
    }

    private void notifyProgressUpdateToListeners(final int currentProgress,
                                                 final int duration,
                                                 final int bufferPercent) {
        if (fragmentListener != null) {
            fragmentListener.onProgressUpdate(currentProgress, duration, bufferPercent);
        }
        if (activityListener != null) {
            activityListener.onProgressUpdate(currentProgress, duration, bufferPercent);
        }
    }

    private void notifyAudioTrackUpdateToListeners() {
        if (fragmentListener != null) {
            fragmentListener.onAudioTrackUpdate();
        }
        if (activityListener != null) {
            activityListener.onAudioTrackUpdate();
        }
    }

    public void useVideoAndSubtitles(final boolean videoAndSubtitlesEnabled) {
        if (playQueue == null) {
            return;
        }

        final boolean enableVideo = videoAndSubtitlesEnabled && !listeningMode;
        isAudioOnly = !enableVideo;

        final var item = playQueue.getItem();
        final boolean hasPendingRecovery =
                item != null && item.getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET;
        final boolean hasTimeline =
                !exoPlayerIsNull() && !simpleExoPlayer.getCurrentTimeline().isEmpty();


        getCurrentStreamInfo().ifPresentOrElse(info -> {
            // In case we don't know the source type, fall back to either video-with-audio, or
            // audio-only source type
            final SourceType sourceType = videoResolver.getStreamSourceType()
                    .orElse(SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY);

            if (hasTimeline || !hasPendingRecovery) {
                // making sure to save playback position before reloadPlayQueueManager()
                setRecovery();
            }

            if (playQueueManagerReloadingNeeded(sourceType, info, getVideoRendererIndex())) {
                reloadPlayQueueManager();
            }
        }, () -> {
            // Direct local sources have no resolver/queue manager to reload.
            if (playQueueManager == null) {
                return;
            }
            /*
            The current metadata may be null sometimes (for e.g. when using an unstable connection
            in livestreams) so we will be not able to execute the block above

            Reload the play queue manager in this case, which is the behavior when we don't know the
            index of the video renderer or playQueueManagerReloadingNeeded returns true
            */
            if (hasTimeline || !hasPendingRecovery) {
                // making sure to save playback position before reloadPlayQueueManager()
                setRecovery();
            }
            reloadPlayQueueManager();
        });

        // Disable or enable video and subtitles renderers depending of the
        // videoAndSubtitlesEnabled value
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enableVideo)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enableVideo));
    }

    public boolean isListeningMode() {
        return listeningMode;
    }

    public void setListeningMode(final boolean enabled) {
        if (listeningMode == enabled) {
            return;
        }
        listeningMode = enabled;
        useVideoAndSubtitles(!enabled && !audioPlayerSelected() && isScreenOn());
        UIs.call(PlayerUi::onListeningModeChanged);
    }

    /** Leave the activity without rebuilding playback or changing its play/pause intent. */
    public void continueInBackgroundOnUiExit() {
        if (!videoPlayerSelected() || exoPlayerIsNull()) {
            return;
        }
        playerType = PlayerType.AUDIO;
        isAudioOnly = true;
        initUIsForCurrentPlayerType();
        notifyPlaybackUpdateToListeners();
    }

    /**
     * Return whether the play queue manager needs to be reloaded when switching player type.
     *
     * <p>
     * The play queue manager needs to be reloaded if the video renderer index is not known and if
     * the content is not an audio content, but also if none of the following cases is met:
     *
     * <ul>
     *     <li>the content is an {@link StreamType#AUDIO_STREAM audio stream}, an
     *     {@link StreamType#AUDIO_LIVE_STREAM audio live stream}, or a
     *     {@link StreamType#POST_LIVE_AUDIO_STREAM ended audio live stream};</li>
     *     <li>the content is a {@link StreamType#LIVE_STREAM live stream} and the source type is a
     *     {@link SourceType#LIVE_STREAM live source};</li>
     *     <li>the content's source is {@link SourceType#VIDEO_WITH_SEPARATED_AUDIO a video stream
     *     with a separated audio source} or has no audio-only streams available <b>and</b> is a
     *     {@link StreamType#VIDEO_STREAM video stream}, an
     *     {@link StreamType#POST_LIVE_STREAM ended live stream}, or a
     *     {@link StreamType#LIVE_STREAM live stream}.
     *     </li>
     * </ul>
     * </p>
     *
     * @param sourceType         the {@link SourceType} of the stream
     * @param streamInfo         the {@link StreamInfo} of the stream
     * @param videoRendererIndex the video renderer index of the video source, if that's a video
     *                           source (or {@link #RENDERER_UNAVAILABLE})
     * @return whether the play queue manager needs to be reloaded
     */
    private boolean playQueueManagerReloadingNeeded(final SourceType sourceType,
                                                    @NonNull final StreamInfo streamInfo,
                                                    final int videoRendererIndex) {
        if (DataSaver.isEnabled(context)) {
            // Re-select a separate source when hiding video, and restore video when showing it.
            // ponytail: duplicate visibility events reload; track source mode if that is frequent.
            return true;
        }
        final StreamType streamType = streamInfo.getStreamType();
        final boolean isStreamTypeAudio = StreamTypeUtil.isAudio(streamType);

        if (videoRendererIndex == RENDERER_UNAVAILABLE && !isStreamTypeAudio) {
            return true;
        }

        // The content is an audio stream, an audio live stream, or a live stream with a live
        // source: it's not needed to reload the play queue manager because the stream source will
        // be the same
        if (isStreamTypeAudio || (streamType == StreamType.LIVE_STREAM
                && sourceType == SourceType.LIVE_STREAM)) {
            return false;
        }

        // The content's source is a video with separated audio or a video with audio -> the video
        // and its fetch may be disabled
        // The content's source is a video with embedded audio and the content has no separated
        // audio stream available: it's probably not needed to reload the play queue manager
        // because the stream source will be probably the same as the current played
        if (sourceType == SourceType.VIDEO_WITH_SEPARATED_AUDIO
                || (sourceType == SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
                && isNullOrEmpty(streamInfo.getAudioStreams()))) {
            // It's not needed to reload the play queue manager only if the content's stream type
            // is a video stream, a live stream or an ended live stream
            return !StreamTypeUtil.isVideo(streamType);
        }

        // Other cases: the play queue manager reload is needed
        return true;
    }
    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Getters
    //////////////////////////////////////////////////////////////////////////*/
    //region Getters

    public Optional<StreamInfo> getCurrentStreamInfo() {
        return Optional.ofNullable(currentMetadata).flatMap(MediaItemTag::getMaybeStreamInfo);
    }

    public int getCurrentState() {
        return currentState;
    }

    public boolean exoPlayerIsNull() {
        return simpleExoPlayer == null;
    }

    public ExoPlayer getExoPlayer() {
        return simpleExoPlayer;
    }

    public boolean isStopped() {
        return exoPlayerIsNull() || simpleExoPlayer.getPlaybackState() == ExoPlayer.STATE_IDLE;
    }

    public boolean isPlaying() {
        return !exoPlayerIsNull() && simpleExoPlayer.isPlaying();
    }

    public boolean getPlayWhenReady() {
        return !exoPlayerIsNull() && simpleExoPlayer.getPlayWhenReady();
    }

    public boolean isLoading() {
        return !exoPlayerIsNull() && simpleExoPlayer.isLoading();
    }

    private boolean isLive() {
        try {
            return !exoPlayerIsNull() && simpleExoPlayer.isCurrentMediaItemDynamic();
        } catch (final IndexOutOfBoundsException e) {
            // Why would this even happen =(... but lets log it anyway, better safe than sorry
            if (DEBUG) {
                Log.d(TAG, "player.isCurrentWindowDynamic() failed: ", e);
            }
            return false;
        }
    }

    public void setPlaybackQuality(@Nullable final String quality) {
        saveStreamProgressState();
        setRecovery();
        videoResolver.setPlaybackQuality(quality);
        reloadPlayQueueManager();
    }

    public void setAudioTrack(@Nullable final String audioTrackId) {
        saveStreamProgressState();
        setRecovery();
        videoResolver.setAudioTrack(audioTrackId);
        audioResolver.setAudioTrack(audioTrackId);
        reloadPlayQueueManager();
    }


    @NonNull
    public Context getContext() {
        return context;
    }

    @NonNull
    public SharedPreferences getPrefs() {
        return prefs;
    }


    public PlayerType getPlayerType() {
        return playerType;
    }

    public boolean audioPlayerSelected() {
        return playerType == PlayerType.AUDIO;
    }

    public boolean videoPlayerSelected() {
        return playerType == PlayerType.MAIN;
    }

    public boolean popupPlayerSelected() {
        return playerType == PlayerType.POPUP;
    }


    @Nullable
    public PlayQueue getPlayQueue() {
        return playQueue;
    }

    public AudioReactor getAudioReactor() {
        return audioReactor;
    }

    public PlayerService getService() {
        return service;
    }

    public boolean isAudioOnly() {
        return isAudioOnly;
    }

    @NonNull
    public DefaultTrackSelector getTrackSelector() {
        return trackSelector;
    }

    @Nullable
    public MediaItemTag getCurrentMetadata() {
        return currentMetadata;
    }

    @Nullable
    public PlayQueueItem getCurrentItem() {
        return currentItem;
    }

    public Optional<PlayerServiceEventListener> getFragmentListener() {
        return Optional.ofNullable(fragmentListener);
    }

    /**
     * @return the user interfaces connected with the player
     */
    @SuppressWarnings("MethodName") // keep the unusual method name
    public PlayerUiList UIs() {
        return UIs;
    }

    /**
     * Get the video renderer index of the current playing stream.
     * <p>
     * This method returns the video renderer index of the current
     * {@link MappingTrackSelector.MappedTrackInfo} or {@link #RENDERER_UNAVAILABLE} if the current
     * {@link MappingTrackSelector.MappedTrackInfo} is null or if there is no video renderer index.
     *
     * @return the video renderer index or {@link #RENDERER_UNAVAILABLE} if it cannot be get
     */
    private int getVideoRendererIndex() {
        final MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector
                .getCurrentMappedTrackInfo();

        if (mappedTrackInfo == null) {
            return RENDERER_UNAVAILABLE;
        }

        // Check every renderer
        return IntStream.range(0, mappedTrackInfo.getRendererCount())
                // Check the renderer is a video renderer and has at least one track
                .filter(i -> !mappedTrackInfo.getTrackGroups(i).isEmpty()
                        && simpleExoPlayer.getRendererType(i) == C.TRACK_TYPE_VIDEO)
                // Return the first index found (there is at most one renderer per renderer type)
                .findFirst()
                // No video renderer index with at least one track found: return unavailable index
                .orElse(RENDERER_UNAVAILABLE);
    }
    //endregion

    /**
     * @return whether the device screen is turned on.
     */
    public boolean isScreenOn() {
        return screenOn;
    }
}
