package org.schabi.newpipe.offline;

import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.Menu;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.preference.PreferenceManager;
import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.service.DownloadManagerService;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.view.MenuItemCompat;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.player.playqueue.OfflinePlayQueue;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerService;

import java.io.IOException;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** A local shelf: no service scraping, account, network download or remote metadata lookup. */
public final class OfflineLibraryActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CompositeDisposable reads = new CompositeDisposable();
    private RecyclerView rows;
    private OfflineShelfAdapter adapter;
    private ItemTouchHelper touchHelper;
    private final OfflineShelfTouchDispatcher touchDispatcher = new OfflineShelfTouchDispatcher();
    private TextView empty;
    private boolean dragging;
    private RecyclerView.ViewHolder dragHolder;
    private int revision;
    private PlayerService playerService;
    private boolean playerBound;
    private final ServiceConnection playerConnection = new ServiceConnection() {
        @Override public void onServiceConnected(final ComponentName name, final IBinder binder) {
            playerService = ((PlayerService.LocalBinder) binder).getService();
            updateCurrentTrack();
        }

        @Override public void onServiceDisconnected(final ComponentName name) {
            playerService = null;
            updateCurrentTrack();
        }
    };
    private TextView summary;
    private TextView playableCount;
    private Button playAll;
    private boolean moving;
    private OfflineLibrary library;
    private boolean resumed;
    private boolean reading;
    private String rendered = "";
    private DownloadManager downloadManager;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(final ComponentName name, final IBinder binder) {
            downloadManager = ((DownloadManagerService.DownloadManagerBinder) binder)
                    .getDownloadManager();
            downloadManager.activateOfflineQueue();
            refresh();
        }

        @Override public void onServiceDisconnected(final ComponentName name) {
            downloadManager = null;
        }
    };
    private final Runnable refresh = this::refresh;
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (final SecurityException ignored) {
                        // The current grant still permits copying; retry may require reselection.
                    }
                    importFile(uri, "", "", 0, null);
                }
            });

    public static void open(final Context context) {
        context.startActivity(new Intent(context, OfflineLibraryActivity.class));
    }

    public static void importDownload(final Context context, final Uri uri, final String title,
                                      final String origin, final int serviceId, final String mime) {
        context.startActivity(new Intent(context, OfflineLibraryActivity.class)
                .setData(uri).putExtra("title", title).putExtra("origin", origin)
                .putExtra("service", serviceId).putExtra("mime", mime));
    }

    @Override protected void onCreate(@Nullable final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.offline_library);
        content.addView(toolbar);
        setContentView(content);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> finish());
        final LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), 0, dp(8), 0);
        content.addView(header);
        playableCount = new TextView(this);
        playableCount.setId(R.id.offline_playable_count);
        header.addView(playableCount, new LinearLayout.LayoutParams(0, -2, 1));
        playAll = button(header, R.string.offline_play_all, () -> playSaved(null));
        playAll.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        playAll.setId(R.id.offline_play_all);
        playAll.setEnabled(false);
        final ScrollView settings = new ScrollView(this);
        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(16), 0, dp(16), dp(8));
        settings.addView(actions);
        settings.setVisibility(View.GONE);
        final Button manage = button(content, R.string.offline_manage, () ->
                settings.setVisibility(settings.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));
        manage.setId(R.id.offline_manage);
        content.addView(settings);
        summary = new TextView(this);
        summary.setId(R.id.offline_storage_summary);
        summary.setPadding(0, dp(8), 0, dp(8));
        actions.addView(summary);
        final TextView orderHint = new TextView(this);
        orderHint.setText(R.string.offline_order_hint);
        actions.addView(orderHint);
        button(actions, R.string.offline_import, () -> picker.launch(
                new String[]{"audio/*", "video/*"}));
        button(actions, R.string.offline_delete_all, () -> {
            if (!moving && !dragging) {
                confirmDelete(null);
            }
        });
        final SwitchCompat wifiOnly = new SwitchCompat(this);
        wifiOnly.setText(R.string.offline_wifi_only);
        wifiOnly.setChecked(PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(OfflineDownloads.WIFI_ONLY, true));
        wifiOnly.setOnCheckedChangeListener((button, checked) ->
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putBoolean(OfflineDownloads.WIFI_ONLY, checked).apply());
        wifiOnly.setMinHeight(dp(48));
        wifiOnly.setSwitchPadding(dp(12));
        actions.addView(wifiOnly);
        empty = new TextView(this);
        empty.setText(R.string.offline_empty);
        empty.setPadding(dp(16), dp(16), dp(16), dp(16));
        empty.setVisibility(View.GONE);
        content.addView(empty);
        rows = new RecyclerView(this) {
            @Override public boolean dispatchTouchEvent(final MotionEvent event) {
                return touchDispatcher.dispatch(event, dragHolder != null,
                        OfflineLibraryActivity.this::cancelShelfDrag, super::dispatchTouchEvent);
            }
        };
        rows.setId(R.id.offline_saved_rows);
        rows.setLayoutManager(new LinearLayoutManager(this));
        adapter = new OfflineShelfAdapter(new OfflineShelfAdapter.Listener() {
            @Override public void play(final OfflineStore.Entry entry) {
                playSaved(entry);
            }

            @Override public void menu(final OfflineStore.Entry entry, final View anchor,
                                       final boolean movementOnly) {
                showItemMenu(entry, anchor, movementOnly);
            }

            @Override public void startDrag(final OfflineShelfAdapter.Holder holder) {
                if (resumed && !moving && dragHolder == null && adapter.beginDrag(holder)) {
                    dragging = true;
                    dragHolder = holder;
                    revision++;
                    playAll.setEnabled(false);
                    touchHelper.startDrag(holder);
                }
            }

            @Override public void move(final OfflineStore.Entry entry, final int offset) {
                if (!moving && !dragging) {
                    commitMove(adapter.moveBy(entry.id, offset));
                }
            }

            @Override public OfflineShelfAdapter.RowDetails details(
                    final OfflineStore.Entry entry) {
                return rowDetails(entry);
            }
        });
        rows.setAdapter(adapter);
        content.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
        touchHelper = createDragHelper();
        try {
            library = OfflineLibrary.get(this);
            bound = bindService(new Intent(this, DownloadManagerService.class), connection,
                    Context.BIND_AUTO_CREATE);
            if (savedInstanceState == null && getIntent().getData() != null) {
                importFile(getIntent().getData(), getIntent().getStringExtra("title"),
                        getIntent().getStringExtra("origin"),
                        getIntent().getIntExtra("service", 0),
                        getIntent().getStringExtra("mime"));
                getIntent().setData(null);
            }
        } catch (final IOException error) {
            summary.setText(R.string.offline_storage_error);
            settings.setVisibility(View.GONE);
        }
    }

    private ItemTouchHelper createDragHelper() {
        return touchDispatcher.createHelper(new ItemTouchHelper.Callback() {
            @Override public int getMovementFlags(@NonNull final RecyclerView recyclerView,
                                                  @NonNull final RecyclerView.ViewHolder holder) {
                return makeMovementFlags(moving ? 0 : ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override public boolean onMove(@NonNull final RecyclerView recyclerView,
                                             @NonNull final RecyclerView.ViewHolder from,
                                             @NonNull final RecyclerView.ViewHolder to) {
                return dragging && adapter.moveDrag(from.getBindingAdapterPosition(),
                        to.getBindingAdapterPosition());
            }

            @Override public void onSwiped(@NonNull final RecyclerView.ViewHolder holder,
                                           final int direction) {
                // Swipe deletion is intentionally disabled; deletion requires the track menu.
            }

            @Override public void clearView(@NonNull final RecyclerView recyclerView,
                                            @NonNull final RecyclerView.ViewHolder holder) {
                super.clearView(recyclerView, holder);
                if (holder == dragHolder) {
                    touchDispatcher.onRecoveryFinished();
                    dragHolder = null;
                    if (dragging) {
                        dragging = false;
                        commitMove(adapter.finishDrag());
                    }
                }
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        touchHelper.attachToRecyclerView(rows);
        refresh();
    }

    @Override protected void onPause() {
        resumed = false;
        revision++;
        // ItemTouchHelper detach alone leaves its selected holder and auto-scroll runnable alive.
        final long now = SystemClock.uptimeMillis();
        final MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        rows.dispatchTouchEvent(cancel);
        cancel.recycle();
        cancelShelfDrag();
        touchHelper.attachToRecyclerView(null);
        dragHolder = null;
        if (playerBound) {
            unbindService(playerConnection);
            playerBound = false;
            playerService = null;
        }
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        reads.dispose();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    private void importFile(final Uri uri, @Nullable final String suppliedTitle,
                            @Nullable final String origin, final int serviceId,
                            @Nullable final String suppliedMime) {
        reads.add(Single.fromCallable(() -> {
            final OfflineLibrary target = OfflineLibrary.get(this);
            String title = suppliedTitle;
            if (title == null || title.isEmpty()) {
                title = getString(R.string.offline_saved_copy);
                try (Cursor cursor = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                        title = cursor.getString(0);
                    }
                }
            }
            final String mime = suppliedMime == null
                    ? getContentResolver().getType(uri) : suppliedMime;
            if (mime == null || !(mime.startsWith("audio/") || mime.startsWith("video/"))) {
                throw new IOException("Unsupported media");
            }
            try (AssetFileDescriptor source = getContentResolver()
                    .openAssetFileDescriptor(uri, "r")) {
                if (source != null && source.getLength() > OfflineStore.DEFAULT_LIMIT
                        - target.store().usedBytes()) {
                    throw new OfflineStore.StorageFullException();
                }
            }
            return target.importUri(uri, title, origin == null ? "" : origin, serviceId, mime);
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(entry -> refresh(), this::showError));
    }

    private void refresh() {
        handler.removeCallbacks(refresh);
        if (!resumed || library == null) {
            return;
        }
        updateCurrentTrack();
        if (!reading && !moving && !dragging) {
            reading = true;
            final int requestedRevision = revision;
            reads.add(Single.fromCallable(() -> library.store().list())
                    .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                    .subscribe(entries -> {
                        reading = false;
                        if (resumed && requestedRevision == revision && !moving && !dragging) {
                            render(entries);
                        }
                    }, error -> {
                        reading = false;
                        if (resumed && requestedRevision == revision) {
                            summary.setText(R.string.offline_storage_error);
                        }
                    }));
        }
        handler.postDelayed(refresh, 1000);
    }

    private void updateCurrentTrack() {
        // Bind only to an already running service, preserving playback and existing listeners.
        if (resumed && !playerBound) {
            playerBound = bindService(new Intent(this, PlayerService.class)
                    .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), playerConnection, 0);
        }
        final Player player = playerService == null ? null : playerService.getPlayer();
        final String url = player == null ? null : player.getCurrentStreamInfo()
                .map(info -> info.getUrl()).orElse(null);
        if (!dragging && !moving) {
            adapter.setCurrentUrl(url);
        }
    }

    private void render(final List<OfflineStore.Entry> entries) {
        final int ready = (int) entries.stream()
                .filter(entry -> entry.state == OfflineStore.State.READY).count();
        playableCount.setText(getString(R.string.offline_playable_count, ready));
        playAll.setText(getString(R.string.offline_play_all_count, ready));
        playAll.setEnabled(ready > 0 && !moving && !dragging);
        summary.setText(getString(R.string.offline_summary,
                library.store().usedBytes() / 1_000_000.0));
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        final StringBuilder signature = new StringBuilder();
        for (final OfflineStore.Entry entry : entries) {
            final OfflineShelfAdapter.RowDetails details = rowDetails(entry);
            signature.append(entry.id).append('|').append(entry.title).append('|')
                    .append(entry.state).append('|').append(details.label).append('|')
                    .append(details.progress).append(';');
        }
        if (!signature.toString().equals(rendered) || adapter.getItemCount() != entries.size()) {
            rendered = signature.toString();
            adapter.setEntries(entries);
        }
    }

    private OfflineShelfAdapter.RowDetails rowDetails(final OfflineStore.Entry entry) {
        final DownloadMission mission = downloadManager == null ? null
                : downloadManager.getOfflineMission(entry.id);
        String label = getString(library.isCapacityFailure(entry.id)
                ? R.string.offline_storage_full : stateText(entry.state));
        if (entry.state == OfflineStore.State.DOWNLOADING) {
            label = getString(mission == null ? R.string.offline_recovery_wait
                    : mission.errCode == DownloadMission.ERROR_INSUFFICIENT_STORAGE
                    || mission.errObject instanceof OfflineStore.StorageFullException
                    ? R.string.offline_storage_full
                    : mission.errCode != DownloadMission.ERROR_NOTHING ? R.string.offline_failed
                    : mission.running ? R.string.offline_downloading
                    : mission.enqueued ? R.string.offline_network_wait
                    : R.string.offline_interrupted);
        }
        int progress = -1;
        if (entry.state == OfflineStore.State.IMPORTING
                || entry.state == OfflineStore.State.DOWNLOADING) {
            final long total = mission == null ? 0 : mission.getLength();
            progress = total <= 0 ? -2 : (int) Math.min(100, 100.0 * mission.done / total);
        }
        return new OfflineShelfAdapter.RowDetails(label + " · "
                + getString(R.string.offline_size, entry.bytes / 1_000_000.0), progress);
    }

    private void showItemMenu(final OfflineStore.Entry entry, final View anchor,
                              final boolean movementOnly) {
        if (moving || dragging || library == null) {
            return;
        }
        final PopupMenu menu = new PopupMenu(this, anchor);
        final int position = adapter.positionOf(entry.id);
        if (position > 0) {
            menu.getMenu().add(Menu.NONE, R.id.offline_move_up, 0, R.string.offline_move_up);
        }
        if (position >= 0 && position + 1 < adapter.getItemCount()) {
            menu.getMenu().add(Menu.NONE, R.id.offline_move_down, 1, R.string.offline_move_down);
        }
        final DownloadMission mission = downloadManager == null ? null
                : downloadManager.getOfflineMission(entry.id);
        if (!movementOnly) {
            if (entry.state == OfflineStore.State.IMPORTING
                    || mission != null && mission.running) {
                menu.getMenu().add(Menu.NONE, R.id.offline_pause, 2, R.string.pause);
            } else if (mission != null || entry.state != OfflineStore.State.READY
                    && entry.state != OfflineStore.State.DOWNLOADING
                    && !"giga".equals(entry.source) && !library.isCopying(entry.id)) {
                menu.getMenu().add(Menu.NONE, R.id.offline_retry, 2, R.string.retry);
            }
            MenuItemCompat.setContentDescription(menu.getMenu()
                    .add(Menu.NONE, R.id.offline_delete, 3, R.string.delete),
                    getString(R.string.offline_delete_description, entry.title));
        }
        menu.setOnMenuItemClickListener(item -> {
            if (moving || dragging) {
                return false;
            }
            final int id = item.getItemId();
            if (id == R.id.offline_move_up || id == R.id.offline_move_down) {
                commitMove(adapter.moveBy(entry.id, id == R.id.offline_move_up ? -1 : 1));
            } else if (id == R.id.offline_delete) {
                confirmDelete(entry);
            } else if (mission != null && downloadManager != null) {
                if (id == R.id.offline_pause) {
                    downloadManager.pauseMission(mission);
                } else {
                    downloadManager.resumeMission(mission);
                }
                rendered = "";
                refresh();
            } else if (id == R.id.offline_pause) {
                change(() -> library.store().interrupt(entry.id));
            } else if (id == R.id.offline_retry) {
                change(() -> library.retry(entry.id));
            }
            return true;
        });
        if (menu.getMenu().size() > 0) {
            menu.show();
        }
    }

    private void cancelShelfDrag() {
        if (dragging) {
            dragging = false;
            revision++;
            adapter.cancelDrag();
            rendered = "";
            refresh();
        }
    }

    private void playSaved(@Nullable final OfflineStore.Entry selected) {
        if (library == null || moving || dragging) {
            return;
        }
        reads.add(Single.fromCallable(() -> {
            if (selected == null) {
                return library.queueAll();
            }
            final OfflineStore.Entry current = library.store().get(selected.id);
            if (current == null || current.state != OfflineStore.State.READY) {
                throw new IOException("Saved item unavailable");
            }
            return library.queue(current);
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(queue -> startSavedQueue(queue, selected != null), this::showError));
    }

    private void startSavedQueue(final OfflinePlayQueue queue, final boolean individual) {
        if (queue.isEmpty()) {
            Toast.makeText(this, R.string.offline_no_playable, Toast.LENGTH_SHORT).show();
            return;
        }
        NavigationHelper.playOnBackgroundPlayer(this, queue, true, null, !individual);
        NavigationHelper.openPlayQueue(this);
    }

    private void commitMove(@Nullable final OfflineShelfOrder.Move move) {
        if (move == null || !resumed) {
            adapter.cancelDrag();
            rendered = "";
            refresh();
            return;
        }
        moving = true;
        revision++;
        playAll.setEnabled(false);
        adapter.setInteractionEnabled(false);
        reads.add(Single.fromCallable(() -> move.commit(library.store()))
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(entries -> {
                    adapter.completeMove(entries);
                    moving = false;
                    adapter.setInteractionEnabled(true);
                    rendered = "";
                    render(entries);
                    for (int i = 0; i < entries.size(); i++) {
                        if (entries.get(i).id.equals(move.id)) {
                            rows.announceForAccessibility(getString(R.string.offline_moved,
                                    entries.get(i).title, i + 1));
                            break;
                        }
                    }
                    refresh();
                }, error -> {
                    adapter.cancelDrag();
                    moving = false;
                    adapter.setInteractionEnabled(true);
                    rendered = "";
                    refresh();
                    showError(error);
                }));
    }

    private static int stateText(final OfflineStore.State state) {
        switch (state) {
            case READY: return R.string.offline_ready;
            case IMPORTING: return R.string.offline_copying;
            case INTERRUPTED: return R.string.offline_interrupted;
            default: return R.string.offline_failed;
        }
    }

    private void confirmDelete(@Nullable final OfflineStore.Entry entry) {
        new AlertDialog.Builder(this).setTitle(R.string.offline_delete_title)
                .setMessage(R.string.offline_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> change(() -> {
                    if (entry == null) {
                        for (final OfflineStore.Entry item : library.store().list()) {
                            deleteItem(item);
                        }
                    } else {
                        deleteItem(entry);
                    }
                })).show();
    }

    private void deleteItem(final OfflineStore.Entry entry) throws IOException {
        if ("giga".equals(entry.source) && downloadManager == null) {
            throw new IOException("Download service is reconnecting");
        }
        final DownloadMission mission = downloadManager == null ? null
                : downloadManager.getOfflineMission(entry.id);
        if (mission != null) {
            if (mission.isPsRunning()) {
                throw new IOException("Wait for media processing to finish before deleting");
            }
            downloadManager.pauseMission(mission);
            downloadManager.deleteMission(mission, true);
        }
        library.store().delete(entry.id);
    }

    private void change(final LocalAction action) {
        reads.add(Single.fromCallable(() -> {
            action.run();
            return true;
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(done -> refresh(), this::showError));
    }

    private void showError(final Throwable error) {
        if (error instanceof OfflineStore.StorageFullException) {
            new AlertDialog.Builder(this).setTitle(R.string.offline_library)
                    .setMessage(R.string.offline_storage_full)
                    .setPositiveButton(android.R.string.ok, null).show();
        } else {
            Toast.makeText(this, R.string.offline_storage_error, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private Button button(final LinearLayout parent, final int text, final Runnable action) {
        final Button button = new Button(this);
        button.setText(text);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setOnClickListener(view -> action.run());
        final boolean horizontal = parent.getOrientation() == LinearLayout.HORIZONTAL;
        parent.addView(button, new LinearLayout.LayoutParams(horizontal ? 0 : -1,
                -2, horizontal ? 1 : 0));
        return button;
    }

    private interface LocalAction {
        void run() throws IOException;
    }
}
