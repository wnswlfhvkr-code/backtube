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
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceManager;
import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.service.DownloadManagerService;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.player.playqueue.OfflinePlayQueue;

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
    private LinearLayout rows;
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
        final ScrollView scroll = new ScrollView(this);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(body);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        playableCount = new TextView(this);
        playableCount.setId(R.id.offline_playable_count);
        playableCount.setTextSize(18);
        body.addView(playableCount);
        playAll = button(body, R.string.offline_play_all, () -> playSaved(null));
        playAll.setId(R.id.offline_play_all);
        playAll.setEnabled(false);
        final TextView orderHint = new TextView(this);
        orderHint.setText(R.string.offline_order_hint);
        orderHint.setPadding(0, dp(4), 0, dp(12));
        body.addView(orderHint);
        summary = new TextView(this);
        summary.setId(R.id.offline_storage_summary);
        summary.setPadding(0, dp(8), 0, dp(8));
        body.addView(summary);
        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setVisibility(View.GONE);
        final Button manage = button(body, R.string.offline_manage, () ->
                actions.setVisibility(actions.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));
        manage.setId(R.id.offline_manage);
        body.addView(actions);
        button(actions, R.string.offline_import, () -> picker.launch(
                new String[]{"audio/*", "video/*"}));
        button(actions, R.string.offline_delete_all, () -> confirmDelete(null));
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
        rows = new LinearLayout(this);
        rows.setId(R.id.offline_saved_rows);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(12), 0, 0);
        body.addView(rows);
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
            actions.setVisibility(View.GONE);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
    }

    @Override protected void onPause() {
        resumed = false;
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
        if (!reading) {
            reading = true;
            reads.add(Single.fromCallable(() -> library.store().list())
                    .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                    .subscribe(entries -> {
                        reading = false;
                        render(entries);
                    }, error -> {
                        reading = false;
                        summary.setText(R.string.offline_storage_error);
                    }));
        }
        handler.postDelayed(refresh, 1000);
    }

    private void render(final List<OfflineStore.Entry> entries) {
        final int ready = (int) entries.stream()
                .filter(entry -> entry.state == OfflineStore.State.READY).count();
        playableCount.setText(getString(R.string.offline_playable_count, ready));
        playAll.setText(getString(R.string.offline_play_all_count, ready));
        playAll.setEnabled(ready > 0 && !moving);
        final StringBuilder signature = new StringBuilder();
        for (final OfflineStore.Entry entry : entries) {
            signature.append(entry.id).append(entry.state).append(entry.bytes)
                    .append(library.isCopying(entry.id))
                    .append(library.isCapacityFailure(entry.id));
            final DownloadMission mission = downloadManager == null ? null
                    : downloadManager.getOfflineMission(entry.id);
            if (mission != null) {
                signature.append(mission.running).append(mission.done)
                        .append(mission.errCode).append(mission.enqueued);
            }
        }
        if (signature.toString().equals(rendered) && rows.getChildCount() > 0) {
            return;
        }
        rendered = signature.toString();
        summary.setText(getString(R.string.offline_summary,
                library.store().usedBytes() / 1_000_000.0));
        rows.removeAllViews();
        if (entries.isEmpty()) {
            final TextView empty = new TextView(this);
            empty.setText(R.string.offline_empty);
            rows.addView(empty);
        }
        int position = 0;
        for (final OfflineStore.Entry entry : entries) {
            final int index = position++;
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setTag(entry.id);
            row.setPadding(dp(16), dp(12), dp(16), dp(8));
            row.setBackgroundResource(R.drawable.offline_saved_item_background);
            final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(12);
            rows.addView(row, rowParams);
            final TextView title = new TextView(this);
            title.setText(entry.title);
            title.setTextSize(18);
            title.setPadding(0, 0, 0, dp(8));
            row.addView(title);
            final TextView status = new TextView(this);
            final DownloadMission mission = downloadManager == null ? null
                    : downloadManager.getOfflineMission(entry.id);
            String stateLabel = getString(library.isCapacityFailure(entry.id)
                    ? R.string.offline_storage_full : stateText(entry.state));
            if (entry.state == OfflineStore.State.DOWNLOADING) {
                stateLabel = getString(mission == null ? R.string.offline_recovery_wait
                        : mission.errCode == DownloadMission.ERROR_INSUFFICIENT_STORAGE
                        || mission.errObject instanceof OfflineStore.StorageFullException
                        ? R.string.offline_storage_full
                        : mission.errCode != DownloadMission.ERROR_NOTHING ? R.string.offline_failed
                        : mission.running ? R.string.offline_downloading
                        : mission.enqueued ? R.string.offline_network_wait
                        : R.string.offline_interrupted);
            }
            status.setText(stateLabel + " · "
                    + getString(R.string.offline_size, entry.bytes / 1_000_000.0));
            row.addView(status);
            if (entry.state == OfflineStore.State.IMPORTING
                    || entry.state == OfflineStore.State.DOWNLOADING) {
                final ProgressBar progress = new ProgressBar(this, null,
                        android.R.attr.progressBarStyleHorizontal);
                final long total = mission == null ? 0 : mission.getLength();
                progress.setIndeterminate(total <= 0);
                if (total > 0) {
                    progress.setProgress((int) Math.min(100, 100.0 * mission.done / total));
                }
                row.addView(progress);
            }
            final LinearLayout actions = new LinearLayout(this);
            row.addView(actions);
            if (entry.state == OfflineStore.State.READY) {
                button(actions, R.string.offline_play, () -> playSaved(entry));
            } else if (entry.state == OfflineStore.State.DOWNLOADING) {
                if (mission != null && mission.running) {
                    button(actions, R.string.pause, () -> downloadManager.pauseMission(mission));
                } else if (mission != null) {
                    button(actions, R.string.retry, () -> downloadManager.resumeMission(mission));
                }
            } else if (entry.state == OfflineStore.State.IMPORTING) {
                button(actions, R.string.pause, () -> change(() -> library.store()
                        .interrupt(entry.id)));
            } else if (!"giga".equals(entry.source) && !library.isCopying(entry.id)) {
                button(actions, R.string.retry, () -> change(() -> library.retry(entry.id)));
            }
            final LinearLayout ordering = new LinearLayout(this);
            row.addView(ordering);
            final Button up = button(ordering, R.string.offline_move_up,
                    () -> moveEntry(entry, -1));
            up.setId(R.id.offline_move_up);
            up.setContentDescription(getString(R.string.offline_move_up_description,
                    entry.title, index + 1, entries.size()));
            up.setEnabled(index > 0 && !moving);
            final Button down = button(ordering, R.string.offline_move_down,
                    () -> moveEntry(entry, 1));
            down.setId(R.id.offline_move_down);
            down.setContentDescription(getString(R.string.offline_move_down_description,
                    entry.title, index + 1, entries.size()));
            down.setEnabled(index < entries.size() - 1 && !moving);
            final Button delete = new Button(this);
            delete.setText(R.string.delete);
            delete.setMinHeight(dp(48));
            delete.setContentDescription(getString(R.string.offline_delete_description,
                    entry.title));
            delete.setOnClickListener(view -> confirmDelete(entry));
            final LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-2, -2);
            deleteParams.gravity = android.view.Gravity.END;
            row.addView(delete, deleteParams);
        }
    }

    private void playSaved(@Nullable final OfflineStore.Entry selected) {
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
                .subscribe(this::startSavedQueue, this::showError));
    }

    private void startSavedQueue(final OfflinePlayQueue queue) {
        if (queue.isEmpty()) {
            Toast.makeText(this, R.string.offline_no_playable, Toast.LENGTH_SHORT).show();
            return;
        }
        NavigationHelper.playOnBackgroundPlayer(this, queue, true);
        NavigationHelper.openPlayQueue(this);
    }

    private void moveEntry(final OfflineStore.Entry entry, final int offset) {
        if (moving) {
            return;
        }
        moving = true;
        playAll.setEnabled(false);
        reads.add(Single.fromCallable(() -> {
            library.store().move(entry.id, offset);
            return library.store().list();
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(entries -> {
                    moving = false;
                    rendered = "";
                    render(entries);
                    for (int i = 0; i < entries.size(); i++) {
                        if (entries.get(i).id.equals(entry.id)) {
                            rows.announceForAccessibility(getString(R.string.offline_moved,
                                    entry.title, i + 1));
                            break;
                        }
                    }
                }, error -> {
                    moving = false;
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
