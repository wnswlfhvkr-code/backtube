package org.schabi.newpipe.faultdiagnostic;

import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The single launcher screen. Intent data and extras are ignored. All disk IO runs on one
 * background thread; results are delivered only while this activity is alive.
 */
public final class LocalFaultProbeActivity extends Activity {
    private static final String OUTBOX_DIRECTORY = "pr7-synthetic-outbox";

    private ExecutorService executor;
    private Handler mainHandler;
    private Context appContext;
    /** Accessed only on the background executor thread. */
    private LocalFaultProbe probe;

    private LinearLayout column;
    private TextView resultView;
    private TextView snapshotView;
    private Button[] buttons;
    private boolean busy;
    private boolean destroyed;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appContext = getApplicationContext();
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        setContentView(buildContent());
        // Automatically show the preserved ledger; it is never reset.
        runOperation(false);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdown();
        super.onDestroy();
    }

    private ScrollView buildContent() {
        final int padding = dp(16);
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(padding, padding, padding, padding);
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(text(getString(R.string.probe_intro)));

        final Button capture = button(R.string.probe_capture);
        final Button captureAgain = button(R.string.probe_capture_again);
        final Button refresh = button(R.string.probe_refresh);
        // Both capture buttons run the very same captureSynthetic(now) operation.
        capture.setOnClickListener(v -> runOperation(true));
        captureAgain.setOnClickListener(v -> runOperation(true));
        refresh.setOnClickListener(v -> runOperation(false));
        buttons = new Button[] {capture, captureAgain, refresh};
        for (final Button b : buttons) {
            column.addView(b);
        }

        resultView = text(getString(R.string.probe_no_result));
        column.addView(resultView);
        final TextView title = text(getString(R.string.probe_snapshot_title));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        column.addView(title);
        snapshotView = text("");
        column.addView(snapshotView);

        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            applyInsets(insets, padding);
            return insets;
        });
        scroll.requestApplyInsets();
        return scroll;
    }

    @SuppressWarnings("deprecation")
    private void applyInsets(final WindowInsets insets, final int padding) {
        final int left;
        final int top;
        final int right;
        final int bottom;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = bars.left;
            top = bars.top;
            right = bars.right;
            bottom = bars.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
        }
        column.setPadding(padding + left, padding + top, padding + right, padding + bottom);
    }

    private TextView text(final String value) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setHorizontallyScrolling(false);
        view.setPadding(0, dp(8), 0, dp(8));
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private Button button(final int label) {
        final Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private int dp(final int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void runOperation(final boolean capture) {
        if (busy || destroyed) {
            return;
        }
        setBusy(true);
        try {
            executor.execute(() -> {
                String status = null;
                String snapshot;
                try {
                    final LocalFaultProbe current = probe();
                    if (capture) {
                        status = current.captureSynthetic(System.currentTimeMillis());
                    }
                    snapshot = current.snapshot(System.currentTimeMillis());
                } catch (final RuntimeException ignored) {
                    status = capture ? LocalFaultProbe.ERROR : null;
                    snapshot = LocalFaultProbe.errorSnapshot();
                }
                final String finalStatus = status;
                final String finalSnapshot = snapshot;
                mainHandler.post(() -> deliver(finalStatus, finalSnapshot));
            });
        } catch (final RejectedExecutionException ignored) {
            setBusy(false);
        }
    }

    /** Background thread only. */
    private LocalFaultProbe probe() {
        if (probe == null) {
            probe = new LocalFaultProbe(
                    new File(appContext.getNoBackupFilesDir(), OUTBOX_DIRECTORY),
                    BuildConfig.PRODUCT_VERSION_CODE, Build.VERSION.SDK_INT);
        }
        return probe;
    }

    private void deliver(final String status, final String snapshot) {
        if (destroyed || isDestroyed()) {
            return;
        }
        if (status != null) {
            resultView.setText(getString(R.string.probe_result_label, status) + "\n"
                    + LocalFaultProbe.meaning(status));
        }
        snapshotView.setText(snapshot);
        setBusy(false);
    }

    private void setBusy(final boolean value) {
        busy = value;
        for (final Button b : buttons) {
            b.setEnabled(!value);
        }
        if (value) {
            snapshotView.setText(R.string.probe_busy);
        }
    }
}
