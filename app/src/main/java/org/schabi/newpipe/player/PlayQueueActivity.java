package org.schabi.newpipe.player;

import static org.schabi.newpipe.QueueItemMenuUtil.openPopupMenu;
import static org.schabi.newpipe.player.helper.PlayerHelper.formatSpeed;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.material.snackbar.Snackbar;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.ActivityPlayerQueueControlBinding;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.fragments.OnScrollBelowItemsListener;
import org.schabi.newpipe.local.dialog.PlaylistDialog;
import org.schabi.newpipe.player.event.PlayerEventListener;
import org.schabi.newpipe.player.gesture.SwipeUpToOpenListener;
import org.schabi.newpipe.player.helper.PlaybackParameterDialog;
import org.schabi.newpipe.player.helper.SleepTimerDialog;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueAdapter;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.PlayQueueItemBuilder;
import org.schabi.newpipe.player.playqueue.PlayQueueItemHolder;
import org.schabi.newpipe.player.playqueue.PlayQueueItemTouchCallback;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.DataSaver;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PermissionHelper;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.image.CoilHelper;

import java.util.List;
import java.util.Optional;

public final class PlayQueueActivity extends AppCompatActivity
        implements PlayerEventListener, SeekBar.OnSeekBarChangeListener,
        View.OnClickListener, PlaybackParameterDialog.Callback {

    private static final String TAG = PlayQueueActivity.class.getSimpleName();

    private static final int SMOOTH_SCROLL_MAXIMUM_DISTANCE = 80;

    private static final int MENU_ID_AUDIO_TRACK = 71;

    private Player player;
    @Nullable
    private SleepTimerDialog sleepTimerDialog;

    private boolean serviceBound;
    private ServiceConnection serviceConnection;

    private boolean seeking;

    private final Handler playbackOptionsHandler = new Handler(Looper.getMainLooper());
    private boolean playbackOptionsUpdatesActive;
    private boolean updatingAutoQueueControl;
    private boolean openingPlaybackScreen;
    private final Runnable playbackOptionsUpdater = new Runnable() {
        @Override
        public void run() {
            updatePlaybackOptions();
            if (playbackOptionsUpdatesActive && player != null) {
                playbackOptionsHandler.postDelayed(this, 1000);
            }
        }
    };

    ////////////////////////////////////////////////////////////////////////////
    // Views
    ////////////////////////////////////////////////////////////////////////////

    private ActivityPlayerQueueControlBinding queueControlBinding;

    private ItemTouchHelper itemTouchHelper;

    private Menu menu;

    ////////////////////////////////////////////////////////////////////////////
    // Activity Lifecycle
    ////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.setTheme(this, ServiceHelper.getSelectedServiceId(this));

        queueControlBinding = ActivityPlayerQueueControlBinding.inflate(getLayoutInflater());
        setContentView(queueControlBinding.getRoot());

        setSupportActionBar(queueControlBinding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_activity_play_queue);
        }

        serviceConnection = getServiceConnection();
        bind();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu m) {
        this.menu = m;
        getMenuInflater().inflate(R.menu.menu_play_queue, m);
        getMenuInflater().inflate(R.menu.menu_play_queue_bg, m);
        buildAudioTrackMenu();
        onMaybeMuteChanged();
        // to avoid null reference
        if (player != null) {
            onPlaybackParameterChanged(player.getPlaybackParameters());
        }
        return true;
    }

    // Allow to setup visibility of menuItems
    @Override
    public boolean onPrepareOptionsMenu(final Menu m) {
        if (player != null) {
            menu.findItem(R.id.action_switch_popup)
                    .setVisible(!player.popupPlayerSelected());
            menu.findItem(R.id.action_switch_background)
                    .setVisible(!player.audioPlayerSelected());
        }
        m.findItem(R.id.action_clear_recommendation_exclusions)
                .setEnabled(player != null && player.hasRecommendationExclusions());
        m.findItem(R.id.action_data_saver).setChecked(DataSaver.isEnabled(this));
        m.findItem(R.id.action_audio_quality).setTitle(getString(R.string.audio_quality_current,
                getString(DataSaver.getQualityLabel(this))));
        return super.onPrepareOptionsMenu(m);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        } else if (itemId == R.id.action_settings) {
            NavigationHelper.openSettings(this);
            return true;
        } else if (player == null) {
            return super.onOptionsItemSelected(item);
        } else if (itemId == R.id.action_data_saver) {
            player.getPrefs().edit().putBoolean(getString(R.string.data_saver_key),
                    !DataSaver.isEnabled(this)).apply();
            updatePlaybackOptions();
            return true;
        } else if (itemId == R.id.action_audio_quality) {
            final String[] values = getResources().getStringArray(R.array.audio_quality_values);
            final String selected = player.getPrefs().getString(
                    getString(R.string.audio_quality_key), DataSaver.BALANCED);
            int selectedIndex = 1;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(selected)) {
                    selectedIndex = i;
                }
            }
            new AlertDialog.Builder(this).setTitle(R.string.audio_quality_title)
                    .setSingleChoiceItems(R.array.audio_quality_labels, selectedIndex,
                            (dialog, which) -> {
                                player.getPrefs().edit().putString(
                                        getString(R.string.audio_quality_key), values[which])
                                        .apply();
                                dialog.dismiss();
                                updatePlaybackOptions();
                            }).setNegativeButton(R.string.cancel, null).show();
            return true;
        } else if (itemId == R.id.action_clear_recommendation_exclusions) {
            player.clearRecommendationExclusions();
            Snackbar.make(queueControlBinding.getRoot(),
                    R.string.personal_exclusions_cleared, Snackbar.LENGTH_SHORT).show();
            updatePlaybackOptions();
            return true;
        } else if (itemId == R.id.action_append_playlist) {
            PlaylistDialog.showForPlayQueue(player, getSupportFragmentManager());
            return true;
        } else if (itemId == R.id.action_playback_speed) {
            openPlaybackParameterDialog();
            return true;
        } else if (itemId == R.id.action_mute) {
            player.toggleMute();
            return true;
        } else if (itemId == R.id.action_system_audio) {
            startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
            return true;
        } else if (itemId == R.id.action_switch_main) {
            openPlaybackScreen();
            return true;
        } else if (itemId == R.id.action_switch_popup) {
            if (PermissionHelper.isPopupEnabledElseAsk(this)) {
                this.player.setRecovery();
                NavigationHelper.playOnPopupPlayer(this, player.getPlayQueue(), true);
            }
            return true;
        } else if (itemId == R.id.action_switch_background) {
            this.player.setRecovery();
            NavigationHelper.playOnBackgroundPlayer(this, player.getPlayQueue(), true);
            return true;
        }

        if (item.getGroupId() == MENU_ID_AUDIO_TRACK) {
            onAudioTrackClick(item.getItemId());
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        dismissSleepTimerDialog();
        playbackOptionsHandler.removeCallbacks(playbackOptionsUpdater);
        super.onDestroy();
        unbind();
    }

    @Override
    protected void onResume() {
        super.onResume();
        openingPlaybackScreen = false;
        playbackOptionsUpdatesActive = true;
        updatePlaybackOptionsUpdates();
    }

    @Override
    protected void onPause() {
        playbackOptionsUpdatesActive = false;
        playbackOptionsHandler.removeCallbacks(playbackOptionsUpdater);
        super.onPause();
    }

    ////////////////////////////////////////////////////////////////////////////
    // Service Connection
    ////////////////////////////////////////////////////////////////////////////

    private void bind() {
        ContextCompat.startForegroundService(this, new Intent(this, PlayerService.class)
                .setAction(PlayerService.ACTION_RESTORE_LAST_SESSION)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        // Note: this code should not really exist, and PlayerHolder should be used instead, but
        // it will be rewritten when NewPlayer will replace the current player.
        final Intent bindIntent = new Intent(this, PlayerService.class);
        bindIntent.setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION);
        final boolean success = bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE);
        if (!success) {
            unbindService(serviceConnection);
        }
        serviceBound = success;
    }

    private void unbind() {
        dismissSleepTimerDialog();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
            if (player != null) {
                player.removeActivityListener(this);
            }

            onQueueUpdate(null);
            if (itemTouchHelper != null) {
                itemTouchHelper.attachToRecyclerView(null);
            }

            itemTouchHelper = null;
            player = null;
        }
    }

    private ServiceConnection getServiceConnection() {
        return new ServiceConnection() {
            @Override
            public void onServiceDisconnected(final ComponentName name) {
                Log.d(TAG, "Player service is disconnected");
                playbackOptionsHandler.removeCallbacks(playbackOptionsUpdater);
                onServiceStopped();
            }

            @Override
            public void onServiceConnected(final ComponentName name, final IBinder service) {
                Log.d(TAG, "Player service is connected");
                dismissSleepTimerDialog();

                if (service instanceof PlayerService.LocalBinder) {
                    final PlayerService playerService =
                            ((PlayerService.LocalBinder) service).getService();
                    if (playerService.restoreLastSession()) {
                        player = playerService.getPlayer();
                    }
                }

                if (player == null || player.getPlayQueue() == null || player.exoPlayerIsNull()) {
                    unbind();
                    Toast.makeText(PlayQueueActivity.this, R.string.personal_no_saved_session,
                            Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    onQueueUpdate(player.getPlayQueue());
                    buildComponents();
                    updatePlaybackOptions();
                    updatePlaybackOptionsUpdates();
                    if (player != null) {
                        player.setActivityListener(PlayQueueActivity.this);
                    }
                }
            }
        };
    }

    ////////////////////////////////////////////////////////////////////////////
    // Component Building
    ////////////////////////////////////////////////////////////////////////////

    private void buildComponents() {
        buildQueue();
        buildMetadata();
        buildSeekBar();
        buildControls();
    }

    private void buildQueue() {
        queueControlBinding.playQueue.setLayoutManager(new LinearLayoutManager(this));
        queueControlBinding.playQueue.setClickable(true);
        queueControlBinding.playQueue.setLongClickable(true);
        queueControlBinding.playQueue.clearOnScrollListeners();
        queueControlBinding.playQueue.addOnScrollListener(getQueueScrollListener());

        itemTouchHelper = new ItemTouchHelper(getItemTouchCallback());
        itemTouchHelper.attachToRecyclerView(queueControlBinding.playQueue);
    }

    private void buildMetadata() {
        queueControlBinding.metadata.setOnClickListener(this);
        queueControlBinding.metadata.setOnTouchListener(new SwipeUpToOpenListener(
                queueControlBinding.metadata, this::openPlaybackScreen));
        ViewCompat.replaceAccessibilityAction(queueControlBinding.metadata,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND,
                getString(R.string.personal_open_current_player), (view, arguments) -> {
                    openPlaybackScreen();
                    return true;
                });
        queueControlBinding.songName.setSelected(true);
        queueControlBinding.artistName.setSelected(true);
    }

    private void openPlaybackScreen() {
        if (player == null || player.getPlayQueue() == null
                || player.getPlayQueue().getItem() == null || openingPlaybackScreen) {
            return;
        }
        openingPlaybackScreen = true;
        player.setRecovery();
        NavigationHelper.playOnMainPlayer(this, player.getPlayQueue(), true);
    }

    private void buildSeekBar() {
        queueControlBinding.seekBar.setOnSeekBarChangeListener(this);
        queueControlBinding.liveSync.setOnClickListener(this);
    }

    private void buildControls() {
        queueControlBinding.controlRecommendationOptions
                .setOnClickListener(view -> showRecommendationOptions());
        queueControlBinding.controlRepeat.setOnClickListener(this);
        queueControlBinding.controlBackward.setOnClickListener(this);
        queueControlBinding.controlFastRewind.setOnClickListener(this);
        queueControlBinding.controlPlayPause.setOnClickListener(this);
        queueControlBinding.controlFastForward.setOnClickListener(this);
        queueControlBinding.controlForward.setOnClickListener(this);
        queueControlBinding.controlRestart.setOnClickListener(this);
        queueControlBinding.controlShuffle.setOnClickListener(this);
        queueControlBinding.controlRecommendation.setOnClickListener(this);
        queueControlBinding.controlSleepTimer.setOnClickListener(this);
        queueControlBinding.controlAutoQueue.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingAutoQueueControl && player != null) {
                player.setAutoQueueEnabled(isChecked);
                updatePlaybackOptions();
            }
        });
    }

    ////////////////////////////////////////////////////////////////////////////
    // Component Helpers
    ////////////////////////////////////////////////////////////////////////////

    private OnScrollBelowItemsListener getQueueScrollListener() {
        return new OnScrollBelowItemsListener() {
            @Override
            public void onScrolledDown(final RecyclerView recyclerView) {
                if (player != null && player.getPlayQueue() != null
                        && !player.getPlayQueue().isComplete()) {
                    player.getPlayQueue().fetch();
                } else {
                    queueControlBinding.playQueue.clearOnScrollListeners();
                }
            }
        };
    }

    private ItemTouchHelper.SimpleCallback getItemTouchCallback() {
        return new PlayQueueItemTouchCallback() {
            @Override
            public void onMove(final int sourceIndex, final int targetIndex) {
                if (player != null) {
                    player.getPlayQueue().move(sourceIndex, targetIndex);
                }
            }

            @Override
            public void onSwiped(final int index) {
                if (index != -1) {
                    player.getPlayQueue().remove(index);
                }
            }
        };
    }

    private PlayQueueItemBuilder.OnSelectedListener getOnSelectedListener() {
        return new PlayQueueItemBuilder.OnSelectedListener() {
            @Override
            public void selected(final PlayQueueItem item, final View view) {
                if (player != null) {
                    player.selectQueueItem(item);
                }
            }

            @Override
            public void held(final PlayQueueItem item, final View view) {
                if (player != null && player.getPlayQueue().indexOf(item) != -1) {
                    openPopupMenu(player.getPlayQueue(), item, view, false,
                            getSupportFragmentManager(), PlayQueueActivity.this);
                }
            }

            @Override
            public void onStartDrag(final PlayQueueItemHolder viewHolder) {
                if (itemTouchHelper != null) {
                    itemTouchHelper.startDrag(viewHolder);
                }
            }
        };
    }

    private void scrollToSelected() {
        if (player == null) {
            return;
        }

        final int currentPlayingIndex = player.getPlayQueue().getIndex();
        final int currentVisibleIndex;
        if (queueControlBinding.playQueue.getLayoutManager() instanceof LinearLayoutManager) {
            final LinearLayoutManager layout =
                    (LinearLayoutManager) queueControlBinding.playQueue.getLayoutManager();
            currentVisibleIndex = layout.findFirstVisibleItemPosition();
        } else {
            currentVisibleIndex = 0;
        }

        final int distance = Math.abs(currentPlayingIndex - currentVisibleIndex);
        if (distance < SMOOTH_SCROLL_MAXIMUM_DISTANCE) {
            queueControlBinding.playQueue.smoothScrollToPosition(currentPlayingIndex);
        } else {
            queueControlBinding.playQueue.scrollToPosition(currentPlayingIndex);
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Component On-Click Listener
    ////////////////////////////////////////////////////////////////////////////

    @Override
    public void onClick(final View view) {
        if (player == null) {
            return;
        }

        if (view.getId() == queueControlBinding.controlRepeat.getId()) {
            player.cycleNextRepeatMode();
        } else if (view.getId() == queueControlBinding.controlBackward.getId()) {
            player.playPrevious();
        } else if (view.getId() == queueControlBinding.controlRestart.getId()) {
            player.restartCurrent();
        } else if (view.getId() == queueControlBinding.controlFastRewind.getId()) {
            player.fastRewind();
        } else if (view.getId() == queueControlBinding.controlPlayPause.getId()) {
            player.playPause();
        } else if (view.getId() == queueControlBinding.controlFastForward.getId()) {
            player.fastForward();
        } else if (view.getId() == queueControlBinding.controlForward.getId()) {
            player.playNext();
        } else if (view.getId() == queueControlBinding.controlShuffle.getId()) {
            player.toggleShuffleModeEnabled();
        } else if (view.getId() == queueControlBinding.controlRecommendation.getId()) {
            onRecommendationClick();
        } else if (view.getId() == queueControlBinding.controlSleepTimer.getId()) {
            showSleepTimerDialog();
        } else if (view.getId() == queueControlBinding.metadata.getId()) {
            scrollToSelected();
        } else if (view.getId() == queueControlBinding.liveSync.getId()) {
            player.seekToDefault();
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Playback Parameters
    ////////////////////////////////////////////////////////////////////////////

    private void openPlaybackParameterDialog() {
        if (player == null) {
            return;
        }
        PlaybackParameterDialog.newInstance(player.getPlaybackSpeed(), player.getPlaybackPitch(),
                player.getPlaybackSkipSilence(), this).show(getSupportFragmentManager(), TAG);
    }

    @Override
    public void onPlaybackParameterChanged(final float playbackTempo, final float playbackPitch,
                                           final boolean playbackSkipSilence) {
        if (player != null) {
            player.setPlaybackParameters(playbackTempo, playbackPitch, playbackSkipSilence);
            onPlaybackParameterChanged(player.getPlaybackParameters());
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Seekbar Listener
    ////////////////////////////////////////////////////////////////////////////

    @Override
    public void onProgressChanged(final SeekBar seekBar, final int progress,
                                  final boolean fromUser) {
        if (fromUser) {
            final String seekTime = Localization.getDurationString(progress / 1000);
            queueControlBinding.currentTime.setText(seekTime);
            queueControlBinding.seekDisplay.setText(seekTime);
        }
    }

    @Override
    public void onStartTrackingTouch(final SeekBar seekBar) {
        seeking = true;
        queueControlBinding.seekDisplay.setVisibility(View.VISIBLE);
    }

    @Override
    public void onStopTrackingTouch(final SeekBar seekBar) {
        if (player != null) {
            player.seekTo(seekBar.getProgress());
        }
        queueControlBinding.seekDisplay.setVisibility(View.GONE);
        seeking = false;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Binding Service Listener
    ////////////////////////////////////////////////////////////////////////////

    @Override
    public void onQueueUpdate(@Nullable final PlayQueue queue) {
        if (queue == null) {
            queueControlBinding.playQueue.setAdapter(null);
        } else {
            final PlayQueueAdapter adapter = new PlayQueueAdapter(this, queue);
            adapter.setSelectedListener(getOnSelectedListener());
            queueControlBinding.playQueue.setAdapter(adapter);
        }
        updatePlaybackOptions();
    }

    @Override
    public void onPlaybackUpdate(final int state, final int repeatMode, final boolean shuffled,
                                 final PlaybackParameters parameters) {
        onStateChanged(state);
        onPlayModeChanged(repeatMode, shuffled);
        onPlaybackParameterChanged(parameters);
        onMaybeMuteChanged();
        updatePlaybackOptions();
    }

    @Override
    public void onProgressUpdate(final int currentProgress, final int duration,
                                 final int bufferPercent) {
        // Set buffer progress
        queueControlBinding.seekBar.setSecondaryProgress((int) (queueControlBinding.seekBar.getMax()
                * ((float) bufferPercent / 100)));

        // Set Duration
        queueControlBinding.seekBar.setMax(duration);
        queueControlBinding.endTime.setText(Localization.getDurationString(duration / 1000));

        // Set current time if not seeking
        if (!seeking) {
            queueControlBinding.seekBar.setProgress(currentProgress);
            queueControlBinding.currentTime.setText(Localization
                    .getDurationString(currentProgress / 1000));
        }

        if (player != null) {
            queueControlBinding.liveSync.setClickable(!player.isLiveEdge());
        }

        // this will make sure progressCurrentTime has the same width as progressEndTime
        final ViewGroup.LayoutParams currentTimeParams =
                queueControlBinding.currentTime.getLayoutParams();
        currentTimeParams.width = Math.max(queueControlBinding.endTime.getWidth(),
                queueControlBinding.endTime.getMinimumWidth());
        queueControlBinding.currentTime.setLayoutParams(currentTimeParams);
    }

    @Override
    public void onMetadataUpdate(final StreamInfo info, final PlayQueue queue) {
        if (info != null) {
            queueControlBinding.songName.setText(info.getName());
            queueControlBinding.artistName.setText(info.getUploaderName());
            CoilHelper.INSTANCE.loadThumbnail(queueControlBinding.currentTrackThumbnail,
                    info.getThumbnails());

            queueControlBinding.endTime.setVisibility(View.GONE);
            queueControlBinding.liveSync.setVisibility(View.GONE);
            switch (info.getStreamType()) {
                case LIVE_STREAM:
                case AUDIO_LIVE_STREAM:
                    queueControlBinding.liveSync.setVisibility(View.VISIBLE);
                    break;
                default:
                    queueControlBinding.endTime.setVisibility(View.VISIBLE);
                    break;
            }

            scrollToSelected();
        }
        updatePlaybackOptions();
    }

    @Override
    public void onServiceStopped() {
        unbind();
        finish();
    }

    ////////////////////////////////////////////////////////////////////////////
    // Binding Service Helper
    ////////////////////////////////////////////////////////////////////////////

    private void onStateChanged(final int state) {
        final ImageButton playPauseButton = queueControlBinding.controlPlayPause;
        switch (state) {
            case Player.STATE_PAUSED:
                playPauseButton.setImageResource(R.drawable.ic_play_arrow);
                playPauseButton.setContentDescription(getString(R.string.play));
                break;
            case Player.STATE_PLAYING:
                playPauseButton.setImageResource(R.drawable.ic_pause);
                playPauseButton.setContentDescription(getString(R.string.pause));
                break;
            case Player.STATE_COMPLETED:
                playPauseButton.setImageResource(R.drawable.ic_replay);
                playPauseButton.setContentDescription(getString(R.string.replay));
                break;
            default:
                break;
        }

        switch (state) {
            case Player.STATE_PAUSED:
            case Player.STATE_PLAYING:
            case Player.STATE_COMPLETED:
                queueControlBinding.controlPlayPause.setClickable(true);
                queueControlBinding.controlPlayPause.setVisibility(View.VISIBLE);
                queueControlBinding.controlProgressBar.setVisibility(View.GONE);
                break;
            default:
                queueControlBinding.controlPlayPause.setClickable(false);
                queueControlBinding.controlPlayPause.setVisibility(View.INVISIBLE);
                queueControlBinding.controlProgressBar.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void onPlayModeChanged(final int repeatMode, final boolean shuffled) {
        switch (repeatMode) {
            case com.google.android.exoplayer2.Player.REPEAT_MODE_OFF:
                queueControlBinding.controlRepeat.setText(R.string.personal_repeat_off);
                break;
            case com.google.android.exoplayer2.Player.REPEAT_MODE_ONE:
                queueControlBinding.controlRepeat.setText(R.string.personal_repeat_one);
                break;
            case com.google.android.exoplayer2.Player.REPEAT_MODE_ALL:
                queueControlBinding.controlRepeat.setText(R.string.personal_repeat_all);
                break;
        }
        queueControlBinding.controlRepeat.setText(getString(
                R.string.personal_repeat_mode, queueControlBinding.controlRepeat.getText()));
        queueControlBinding.controlRepeat.setContentDescription(
                queueControlBinding.controlRepeat.getText());

        final int shuffleAlpha = shuffled ? 255 : 77;
        queueControlBinding.controlShuffle.setImageAlpha(shuffleAlpha);
    }

    private void onPlaybackParameterChanged(@Nullable final PlaybackParameters parameters) {
        if (parameters != null && menu != null && player != null) {
            final MenuItem item = menu.findItem(R.id.action_playback_speed);
            item.setTitle(formatSpeed(parameters.speed));
        }
    }

    private void onMaybeMuteChanged() {
        if (menu != null && player != null) {
            final MenuItem item = menu.findItem(R.id.action_mute);

            //Change the mute-button item in ActionBar
            //1) Text change:
            item.setTitle(player.isMuted() ? R.string.unmute : R.string.mute);

            //2) Icon change accordingly to current App Theme
            // using rootView.getContext() because getApplicationContext() didn't work
            item.setIcon(player.isMuted() ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
        }
    }

    private void updatePlaybackOptionsUpdates() {
        playbackOptionsHandler.removeCallbacks(playbackOptionsUpdater);
        if (playbackOptionsUpdatesActive && player != null) {
            playbackOptionsHandler.post(playbackOptionsUpdater);
        }
    }

    private void updatePlaybackOptions() {
        if (player == null) {
            return;
        }

        if (getSupportActionBar() != null) {
            final int status = player.audioPlayerSelected()
                    ? (DataSaver.isEnabled(this) ? R.string.audio_only_status
                    : R.string.background_audio_status) : R.string.video_playback_status;
            getSupportActionBar().setSubtitle(getString(status,
                    getString(DataSaver.getQualityLabel(this))));
        }

        updatingAutoQueueControl = true;
        queueControlBinding.controlAutoQueue.setChecked(player.isAutoQueueEnabled());
        updatingAutoQueueControl = false;

        SleepTimerDialog.updateButton(queueControlBinding.controlSleepTimer, player);

        updateRecommendationPreview();
    }

    private void updateRecommendationPreview() {
        final Player.RecommendationStatus status = player.getRecommendationStatus();
        final PlayQueueItem nextItem = player.getNextRecommendation();
        final String title;
        final int buttonText;
        boolean buttonEnabled = true;

        switch (status) {
            case LOADING:
                title = getString(R.string.personal_recommendation_loading);
                buttonText = R.string.personal_recommendation_loading;
                buttonEnabled = false;
                break;
            case READY:
                title = nextItem == null ? getString(R.string.personal_no_recommendation)
                        : getString(R.string.personal_next_item) + ": " + nextItem.getTitle();
                if (player.canReplaceNextRecommendation()) {
                    buttonText = R.string.personal_recommendation_replace;
                } else {
                    buttonText = R.string.personal_recommendation_queued;
                    buttonEnabled = false;
                }
                break;
            case ERROR:
                title = getString(R.string.personal_recommendation_failed);
                buttonText = R.string.personal_recommendation_retry;
                break;
            case EMPTY:
            case IDLE:
            default:
                title = getString(R.string.personal_no_recommendation);
                buttonText = R.string.personal_recommendation_load;
                break;
        }

        queueControlBinding.recommendationTitle.setText(title);
        queueControlBinding.controlRecommendation.setText(buttonText);
        queueControlBinding.controlRecommendation.setContentDescription(getString(buttonText));
        queueControlBinding.controlRecommendation.setEnabled(buttonEnabled);
        queueControlBinding.controlRecommendationOptions.setVisibility(
                status == Player.RecommendationStatus.READY && nextItem != null
                        && player.canReplaceNextRecommendation() ? View.VISIBLE : View.GONE);
        queueControlBinding.recommendationPreview.setContentDescription(getString(
                R.string.personal_recommendation_next) + ": " + title);
    }

    private void onRecommendationClick() {
        switch (player.getRecommendationStatus()) {
            case READY:
                if (player.canReplaceNextRecommendation()) {
                    player.replaceNextRecommendation();
                }
                break;
            case IDLE:
            case EMPTY:
            case ERROR:
                player.loadNextRecommendation();
                break;
            case LOADING:
            default:
                return;
        }
        updatePlaybackOptions();
    }

    private void showRecommendationOptions() {
        if (player == null || !player.canReplaceNextRecommendation()) {
            return;
        }
        final PlayQueueItem candidate = player.getNextRecommendation();
        if (candidate == null) {
            return;
        }
        final PopupMenu options = new PopupMenu(this,
                queueControlBinding.controlRecommendationOptions);
        options.getMenu().add(0, 0, 0, R.string.personal_exclude_video);
        options.getMenu().add(0, 1, 1, R.string.personal_exclude_channel)
                .setEnabled(candidate.getUploaderUrl() != null
                        && !candidate.getUploaderUrl().isBlank());
        options.setOnMenuItemClickListener(choice -> {
            final boolean channel = choice.getItemId() == 1;
            if (player != null && player.getNextRecommendation() == candidate
                    && player.excludeNextRecommendation(channel)) {
                Snackbar.make(queueControlBinding.getRoot(), channel
                                ? R.string.personal_channel_excluded
                                : R.string.personal_video_excluded, Snackbar.LENGTH_LONG)
                        .setAction(R.string.personal_exclusion_undo, view -> {
                            if (player != null) {
                                player.undoRecommendationExclusion(candidate, channel);
                                updatePlaybackOptions();
                            }
                        }).show();
                updatePlaybackOptions();
            }
            return true;
        });
        options.show();
    }

    private void showSleepTimerDialog() {
        dismissSleepTimerDialog();
        if (player != null) {
            sleepTimerDialog = new SleepTimerDialog(this, player, () -> {
                updatePlaybackOptions();
                updatePlaybackOptionsUpdates();
            });
            sleepTimerDialog.show();
        }
    }

    private void dismissSleepTimerDialog() {
        if (sleepTimerDialog != null) {
            sleepTimerDialog.dismiss();
            sleepTimerDialog = null;
        }
    }

    @Override
    public void onAudioTrackUpdate() {
        buildAudioTrackMenu();
    }

    private void buildAudioTrackMenu() {
        if (menu == null) {
            return;
        }

        final MenuItem audioTrackSelector = menu.findItem(R.id.action_audio_track);
        final List<AudioStream> availableStreams =
                Optional.ofNullable(player)
                        .map(Player::getCurrentMetadata)
                        .flatMap(MediaItemTag::getMaybeAudioTrack)
                        .map(MediaItemTag.AudioTrack::getAudioStreams)
                        .orElse(null);
        final Optional<AudioStream> selectedAudioStream = Optional.ofNullable(player)
                .flatMap(Player::getSelectedAudioStream);

        if (availableStreams == null || availableStreams.size() < 2
                || selectedAudioStream.isEmpty()) {
            audioTrackSelector.setVisible(false);
        } else {
            final SubMenu audioTrackMenu = audioTrackSelector.getSubMenu();
            audioTrackMenu.clear();

            for (int i = 0; i < availableStreams.size(); i++) {
                final AudioStream audioStream = availableStreams.get(i);
                audioTrackMenu.add(MENU_ID_AUDIO_TRACK, i, Menu.NONE,
                        Localization.audioTrackName(this, audioStream));
            }

            final AudioStream s = selectedAudioStream.get();
            final String trackName = Localization.audioTrackName(this, s);
            audioTrackSelector.setTitle(
                    getString(R.string.play_queue_audio_track, trackName));

            final String shortName = s.getAudioLocale() != null
                    ? s.getAudioLocale().getLanguage() : trackName;
            audioTrackSelector.setTitleCondensed(
                    shortName.substring(0, Math.min(shortName.length(), 2)));
            audioTrackSelector.setVisible(true);
        }
    }

    /**
     * Called when an item from the audio track selector is selected.
     *
     * @param itemId index of the selected item
     */
    private void onAudioTrackClick(final int itemId) {
        if (player.getCurrentMetadata() == null) {
            return;
        }
        player.getCurrentMetadata().getMaybeAudioTrack().ifPresent(audioTrack -> {
            final List<AudioStream> availableStreams = audioTrack.getAudioStreams();
            final int selectedStreamIndex = audioTrack.getSelectedAudioStreamIndex();
            if (selectedStreamIndex == itemId || availableStreams.size() <= itemId) {
                return;
            }

            final String newAudioTrack = availableStreams.get(itemId).getAudioTrackId();
            player.setAudioTrack(newAudioTrack);
        });
    }
}
