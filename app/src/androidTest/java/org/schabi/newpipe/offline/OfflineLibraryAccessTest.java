package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.App;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.download.DownloadDialog;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Access paths use a generated WAV only; no remote media is downloaded. */
@RunWith(AndroidJUnit4.class)
public class OfflineLibraryAccessTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private OfflineLibrary library;
    private OfflineStore.Entry saved;
    private File source;

    @Before
    public void setUp() throws Exception {
        library = OfflineLibrary.get(context);
        source = File.createTempFile("offline-access-", ".wav", context.getCacheDir());
        final int length = 16000;
        final ByteBuffer wav = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length);
        try (FileOutputStream out = new FileOutputStream(source)) {
            out.write(wav.array());
        }
        saved = library.store().create("Generated offline access WAV", "", 0,
                Uri.fromFile(source).toString(), "audio/wav");
        try (FileInputStream input = new FileInputStream(source)) {
            library.store().copy(saved.id, input);
        }
    }

    @After
    public void tearDown() throws Exception {
        main(() -> {
            for (final Stage stage : Stage.values()) {
                if (stage == Stage.DESTROYED || stage == Stage.PRE_ON_CREATE) {
                    continue;
                }
                for (final Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(stage))) {
                    if (!activity.isFinishing() && (activity instanceof MainActivity
                            || activity instanceof OfflineLibraryActivity)) {
                        activity.finish();
                    }
                }
            }
            return null;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        if (saved != null) {
            library.store().delete(saved.id);
        }
        if (source != null) {
            source.delete();
        }
    }

    @Test
    public void mainDrawerOpensSavedListWithoutADownloadDialog() throws Exception {
        launchMain();
        main(() -> {
            final View drawer = active(MainActivity.class).findViewById(R.id.navigation);
            ((DrawerLayout) drawer.getParent()).openDrawer(GravityCompat.START);
            return null;
        });
        tapText(R.string.offline_library, R.id.navigation);
        awaitShelf();
        assertTrue(source.isFile());
        assertEquals(OfflineStore.State.READY, library.store().get(saved.id).state);
    }

    @Test
    public void savedNotificationActionAndContentOpenThePersistentList() throws Exception {
        launchMain();
        final Notification notification = OfflineSaveNotifications.build(context, saved.title);
        assertEquals(context.getString(R.string.offline_open_list), notification.actions[0].title);
        notification.actions[0].actionIntent.send();
        awaitShelf();
        main(() -> {
            active(OfflineLibraryActivity.class).finish();
            return null;
        });
        await(() -> active(MainActivity.class) != null);
        notification.contentIntent.send();
        awaitShelf();
        assertEquals(OfflineStore.State.READY, library.store().get(saved.id).state);
    }

    @Test
    public void offlineSelectorKeepsItsModeAfterRotation() throws Exception {
        launchMain();
        final StreamInfo info = new StreamInfo(1, "offline-selector-fixture", "fixture",
                StreamType.AUDIO_STREAM, "Generated selection WAV", "fixture", 0);
        info.setAudioStreams(List.of(new AudioStream.Builder().setId("local-audio")
                .setContent(Uri.fromFile(source).toString(), true).setMediaFormat(MediaFormat.WAV)
                .setAverageBitrate(128).build()));
        main(() -> {
            final MainActivity activity = active(MainActivity.class);
            DownloadDialog.forOffline(activity, info).show(activity.getSupportFragmentManager(),
                    "offline_access_selector");
            return null;
        });
        await(this::offlineSelectorVisible);
        main(() -> {
            active(MainActivity.class).setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            return null;
        });
        await(() -> active(MainActivity.class) != null && active(MainActivity.class).getResources()
                .getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && offlineSelectorVisible());
    }

    @Test
    public void oversizedLocalImportShowsCapacityFeedbackAndKeepsExistingSave() throws Exception {
        final File oversized = File.createTempFile("offline-capacity-", ".wav",
                context.getCacheDir());
        try {
            try (RandomAccessFile file = new RandomAccessFile(oversized, "rw")) {
                file.setLength(OfflineStore.DEFAULT_LIMIT + 1);
            }
            final int entriesBefore = library.store().list().size();
            InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(context, OfflineLibraryActivity.class)
                            .setData(Uri.fromFile(oversized))
                            .putExtra("title", "Generated oversized WAV")
                            .putExtra("mime", "audio/wav")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitAccessibilityText(context.getString(R.string.offline_storage_full));
            assertEquals(entriesBefore, library.store().list().size());
            assertEquals(OfflineStore.State.READY, library.store().get(saved.id).state);
            assertTrue(library.store().file(saved).isFile());
        } finally {
            oversized.delete();
        }
    }

    private boolean offlineSelectorVisible() {
        final MainActivity activity = active(MainActivity.class);
        if (activity == null) {
            return false;
        }
        final androidx.fragment.app.Fragment fragment = activity.getSupportFragmentManager()
                .findFragmentByTag("offline_access_selector");
        if (fragment == null || fragment.getView() == null) {
            return false;
        }
        final View view = fragment.getView();
        final Toolbar toolbar = view.findViewById(R.id.toolbar);
        return toolbar.isShown() && context.getString(R.string.offline_save)
                .contentEquals(toolbar.getTitle())
                && context.getString(R.string.offline_save)
                .contentEquals(toolbar.getMenu().findItem(R.id.okay).getTitle())
                && !view.findViewById(R.id.subtitle_button).isShown();
    }

    private void launchMain() throws Exception {
        final boolean permissionPrompt = Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !App.getInstance().getNotificationsRequested();
        final android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation();
        final AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,
                MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        if (permissionPrompt) {
            final long deadline = SystemClock.uptimeMillis() + 10000;
            boolean denied = false;
            while (!denied && SystemClock.uptimeMillis() < deadline) {
                final AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                if (root != null) {
                    final List<AccessibilityNodeInfo> nodes = root
                            .findAccessibilityNodeInfosByViewId(root.getPackageName()
                                    + ":id/permission_deny_button");
                    for (final AccessibilityNodeInfo node : nodes) {
                        if (node.isVisibleToUser()) {
                            denied |= node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        }
                        node.recycle();
                    }
                    root.recycle();
                }
                SystemClock.sleep(50);
            }
            assertTrue("notification prompt was not dismissed", denied);
        }
        await(() -> active(MainActivity.class) != null
                && active(MainActivity.class).hasWindowFocus());
    }

    private void awaitShelf() throws Exception {
        await(() -> {
            final OfflineLibraryActivity shelf = active(OfflineLibraryActivity.class);
            if (shelf == null || !shelf.hasWindowFocus()) {
                return false;
            }
            final TextView summary = shelf.findViewById(R.id.offline_storage_summary);
            final ArrayList<View> matches = new ArrayList<>();
            shelf.findViewById(R.id.offline_saved_rows).findViewsWithText(matches, saved.title,
                    View.FIND_VIEWS_WITH_TEXT);
            if (matches.isEmpty()) {
                return false;
            }
            final View title = matches.get(0);
            title.requestRectangleOnScreen(new Rect(0, 0, title.getWidth(),
                    title.getHeight()), true);
            final Rect visible = new Rect();
            return summary.isShown() && summary.getText().toString().contains("500")
                    && title.isShown() && title.getHeight() > 0
                    && title.getGlobalVisibleRect(visible)
                    && visible.height() == title.getHeight();
        });
    }

    private void tapText(final int stringId, final int rootId) throws Exception {
        final Rect[] screen = new Rect[1];
        await(() -> {
            final View root = active(MainActivity.class).findViewById(rootId);
            final ArrayList<View> matches = new ArrayList<>();
            root.findViewsWithText(matches, context.getString(stringId), View.FIND_VIEWS_WITH_TEXT);
            for (final View view : matches) {
                view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(),
                        view.getHeight()), true);
                final Rect visible = new Rect();
                if (view.isShown() && view.getGlobalVisibleRect(visible)
                        && visible.height() == view.getHeight() && view.getWidth() > 0
                        && visible.width() == view.getWidth()) {
                    final int[] location = new int[2];
                    view.getLocationOnScreen(location);
                    screen[0] = new Rect(location[0], location[1], location[0] + view.getWidth(),
                            location[1] + view.getHeight());
                    return true;
                }
            }
            return false;
        });
        final long start = SystemClock.uptimeMillis();
        for (final int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            final MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                    screen[0].centerX(), screen[0].centerY(), 0);
            InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
            event.recycle();
        }
    }

    private void awaitAccessibilityText(final String text) {
        final long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            final AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            boolean found = false;
            if (root != null) {
                for (final AccessibilityNodeInfo node
                        : root.findAccessibilityNodeInfosByText(text)) {
                    found |= node.isVisibleToUser();
                    node.recycle();
                }
                root.recycle();
            }
            if (found) {
                return;
            }
            SystemClock.sleep(50);
        }
        assertTrue("capacity feedback did not appear", false);
    }

    private static <T extends Activity> T active(final Class<T> type) {
        for (final Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)) {
            if (type.isInstance(activity)) {
                return type.cast(activity);
            }
        }
        return null;
    }

    private static void await(final BooleanSupplier condition) throws Exception {
        final long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            if (main(condition::getAsBoolean)) {
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("offline UI condition timed out", main(condition::getAsBoolean));
    }

    private static <T> T main(final Supplier<T> action) throws Exception {
        final FutureTask<T> task = new FutureTask<>(action::get);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}
