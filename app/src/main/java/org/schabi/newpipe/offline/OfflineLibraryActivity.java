package org.schabi.newpipe.offline;

import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.Intent;
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

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
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
        summary = new TextView(this);
        summary.setPadding(24, 16, 24, 16);
        content.addView(summary);
        final LinearLayout actions = new LinearLayout(this);
        content.addView(actions);
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
        content.addView(wifiOnly);
        final ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(24, 8, 24, 24);
        scroll.addView(rows);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
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
            return target.importUri(uri, title, origin == null ? "" : origin, serviceId, mime);
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(entry -> refresh(), error -> showError()));
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
        final StringBuilder signature = new StringBuilder();
        for (final OfflineStore.Entry entry : entries) {
            signature.append(entry.id).append(entry.state).append(entry.bytes)
                    .append(library.isCopying(entry.id));
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
        for (final OfflineStore.Entry entry : entries) {
            final TextView title = new TextView(this);
            title.setText(entry.title);
            title.setTextSize(18);
            title.setPadding(0, 20, 0, 8);
            rows.addView(title);
            final TextView status = new TextView(this);
            final String expiry = entry.state == OfflineStore.State.READY
                    ? " · " + getString(R.string.offline_expires,
                    DateFormat.getDateTimeInstance().format(new Date(entry.expiresAt))) : "";
            final DownloadMission mission = downloadManager == null ? null
                    : downloadManager.getOfflineMission(entry.id);
            String stateLabel = getString(stateText(entry.state));
            if (entry.state == OfflineStore.State.DOWNLOADING) {
                stateLabel = getString(mission == null ? R.string.offline_recovery_wait
                        : mission.errCode != DownloadMission.ERROR_NOTHING ? R.string.offline_failed
                        : mission.running ? R.string.offline_downloading
                        : mission.enqueued ? R.string.offline_network_wait
                        : R.string.offline_interrupted);
            }
            status.setText(stateLabel + " · "
                    + getString(R.string.offline_size, entry.bytes / 1_000_000.0) + expiry);
            rows.addView(status);
            if (entry.state == OfflineStore.State.IMPORTING
                    || entry.state == OfflineStore.State.DOWNLOADING) {
                final ProgressBar progress = new ProgressBar(this, null,
                        android.R.attr.progressBarStyleHorizontal);
                final long total = mission == null ? 0 : mission.getLength();
                progress.setIndeterminate(total <= 0);
                if (total > 0) {
                    progress.setProgress((int) Math.min(100, 100.0 * mission.done / total));
                }
                rows.addView(progress);
            }
            final LinearLayout actions = new LinearLayout(this);
            rows.addView(actions);
            if (entry.state == OfflineStore.State.READY) {
                button(actions, R.string.offline_play, () -> {
                    NavigationHelper.playOnBackgroundPlayer(this, library.queue(entry), true);
                    NavigationHelper.openPlayQueue(this);
                });
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
            button(actions, R.string.delete, () -> confirmDelete(entry));
        }
    }

    private static int stateText(final OfflineStore.State state) {
        switch (state) {
            case READY: return R.string.offline_ready;
            case IMPORTING: return R.string.offline_copying;
            case INTERRUPTED: return R.string.offline_interrupted;
            case EXPIRED: return R.string.offline_expired;
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
                .subscribe(done -> refresh(), error -> showError()));
    }

    private void showError() {
        Toast.makeText(this, R.string.offline_storage_error, Toast.LENGTH_LONG).show();
    }

    private void button(final LinearLayout parent, final int text, final Runnable action) {
        final Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private interface LocalAction {
        void run() throws IOException;
    }
}
