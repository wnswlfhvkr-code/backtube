package org.schabi.newpipe.info_list.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListAdapter;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.fragments.list.playlist.Batch10TestActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android dialog contents with generated, non-network stream metadata. */
@RunWith(AndroidJUnit4.class)
public class OnlineVideoLongPressMenuDeviceTest {
    @Test public void onlineVideoDialogShowsOfflineSaveAndRetainsOtherItems() throws Exception {
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final var context = instrumentation.getTargetContext();
        final Intent launch = new Intent(context, Batch10TestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Batch10TestActivity activity = (Batch10TestActivity)
                instrumentation.startActivitySync(launch);
        final AtomicReference<AlertDialog> shown = new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(() -> {
                final FrameLayout content = new FrameLayout(activity);
                content.setId(View.generateViewId());
                activity.setContentView(content);
                final Fragment fragment = new Fragment();
                activity.getSupportFragmentManager().beginTransaction()
                        .add(content.getId(), fragment).commitNow();
                final StreamInfoItem item = new StreamInfoItem(0,
                        "https://example.invalid/watch?v=generated-menu", "Generated video",
                        StreamType.VIDEO_STREAM);
                final InfoItemDialog menu = new InfoItemDialog.Builder(activity, activity,
                        fragment, item).create();
                try {
                    final Field dialog = InfoItemDialog.class.getDeclaredField("dialog");
                    dialog.setAccessible(true);
                    shown.set((AlertDialog) dialog.get(menu));
                } catch (final ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                menu.show();
            });
            instrumentation.waitForIdleSync();
            final List<String> labels = new ArrayList<>();
            instrumentation.runOnMainSync(() -> {
                assertTrue(shown.get().isShowing());
                final ListAdapter adapter = shown.get().getListView().getAdapter();
                for (int i = 0; i < adapter.getCount(); i++) {
                    labels.add(adapter.getItem(i).toString());
                }
            });
            assertEquals(1, labels.stream().filter(
                    label -> label.equals(activity.getString(R.string.offline_save))).count());
            assertFalse(labels.contains(activity.getString(R.string.start_here_on_background)));
            assertFalse(labels.contains(activity.getString(R.string.start_here_on_popup)));
            assertFalse(labels.contains(activity.getString(R.string.open_in_browser)));
            assertTrue(labels.contains(activity.getString(R.string.download)));
            assertTrue(labels.contains(activity.getString(R.string.add_to_playlist)));
            assertTrue(labels.contains(activity.getString(R.string.share)));

            final Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                final File image = new File(activity.getExternalFilesDir(null),
                        "backtube-online-video-longpress-menu.png");
                try (FileOutputStream output = new FileOutputStream(image)) {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
                }
                screenshot.recycle();
            }
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (shown.get() != null) {
                    shown.get().dismiss();
                }
                activity.finish();
            });
        }
    }
}
