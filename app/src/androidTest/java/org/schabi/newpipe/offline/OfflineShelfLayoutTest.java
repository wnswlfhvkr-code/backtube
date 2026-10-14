package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.FutureTask;

/**
 * Real resource/binder measurement, without launching an Activity or playback service.
 * This fixture does not establish visual, TalkBack, or system-font-setting device acceptance.
 */
@RunWith(AndroidJUnit4.class)
public class OfflineShelfLayoutTest {
    private static final String TITLE = "A long saved recording title with enough words to wrap "
            + "on a narrow phone while keeping the management targets separate";

    private final Context targetContext = InstrumentationRegistry.getInstrumentation()
            .getTargetContext();
    @Rule public TemporaryFolder temporary = new TemporaryFolder(targetContext.getCacheDir());

    private final List<OfflineStore.Entry> entries = new ArrayList<>();

    @Before
    public void createOnlyOwnedEntries() throws Exception {
        // Real durable entries live exclusively in this rule-owned cache directory.
        // File copying happens on the instrumentation thread, never the UI thread.
        final OfflineStore store = new OfflineStore(temporary.newFolder("layout-store"), 1024);
        final OfflineStore.Entry ready = store.create(TITLE, "", 0,
                "content://layout-fixture/ready", "audio/wav");
        store.copy(ready.id, new ByteArrayInputStream(new byte[32]));
        entries.add(store.get(ready.id));
        entries.add(store.create(TITLE, "", 0,
                "content://layout-fixture/importing", "audio/wav"));
        entries.add(store.beginDownload(TITLE, "", 0, "audio/wav"));
        final OfflineStore.Entry failed = store.create(TITLE, "", 0,
                "content://layout-fixture/failed", "audio/wav");
        store.fail(failed.id);
        entries.add(store.get(failed.id));
        assertEquals(OfflineStore.State.READY, entries.get(0).state);
        assertEquals(OfflineStore.State.IMPORTING, entries.get(1).state);
        assertEquals(OfflineStore.State.DOWNLOADING, entries.get(2).state);
        assertEquals(OfflineStore.State.FAILED, entries.get(3).state);
    }

    @Test
    public void rowsFitAt320DpAndGrowForLargeText() throws Exception {
        checkWidth(320);
    }

    @Test
    public void rowsFitAt360DpAndGrowForLargeText() throws Exception {
        checkWidth(360);
    }

    @Test
    public void shortTitleKeepsFullSizedTargetsAtBothFontScales() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder("short-title"), 1024);
        final OfflineStore.Entry shortEntry = store.create("Short", "", 0,
                "content://layout-fixture/short", "audio/wav");
        store.copy(shortEntry.id, new ByteArrayInputStream(new byte[32]));
        final FutureTask<Void> task = new FutureTask<>(() -> {
            for (final int width : new int[]{320, 360}) {
                measureAndCheck(store.get(shortEntry.id), width, 1.0f, false);
                measureAndCheck(store.get(shortEntry.id), width, 2.0f, false);
            }
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        task.get();
    }

    private void checkWidth(final int widthDp) throws Exception {
        final FutureTask<Void> task = new FutureTask<>(() -> {
            for (final OfflineStore.Entry entry : entries) {
                final int defaultHeight = measureAndCheck(entry, widthDp, 1.0f, true);
                final int largeHeight = measureAndCheck(entry, widthDp, 2.0f, true);
                assertTrue(entry.state + "/" + widthDp
                        + "dp: long-title row must grow to accommodate large text",
                        largeHeight > defaultHeight);
            }
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        task.get();
    }

    private int measureAndCheck(final OfflineStore.Entry entry, final int widthDp,
                               final float fontScale, final boolean expectWrapping) {
        final Context context = configuredContext(widthDp, fontScale);
        final String stage = entry.state + "/" + widthDp + "dp/font=" + fontScale;
        assertEquals(stage + " fixture font scale", fontScale,
                context.getResources().getConfiguration().fontScale, 0.0f);
        assertEquals(stage + " fixture width", widthDp,
                context.getResources().getConfiguration().screenWidthDp);

        // The actual adapter inflates offline_shelf_item and applies the production binder.
        // A detached RecyclerView only supplies inflation LayoutParams; no click is performed.
        final RecyclerView parent = new RecyclerView(context);
        parent.setLayoutManager(new LinearLayoutManager(context));
        final OfflineShelfAdapter adapter = new OfflineShelfAdapter(new LayoutListener(context));
        adapter.setEntries(List.of(entry));
        final OfflineShelfAdapter.Holder holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);
        final ViewGroup row = (ViewGroup) holder.itemView;
        final int widthPx = dp(context, widthDp);
        row.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());

        assertEquals(stage + " measured width", widthPx, row.getWidth());
        assertTrue(stage + " row has height", row.getHeight() > 0);
        assertEquals(stage + " real bound title", entry.title, holder.title.getText().toString());
        assertEquals(stage + " local thumbnail visible", View.VISIBLE,
                holder.thumbnail.getVisibility());
        assertTextFits(stage + " title", holder.title);
        assertTextFits(stage + " details", holder.details);
        if (expectWrapping) {
            assertTrue(stage + " long title exercises wrapping", holder.title.getLayout()
                    .getLineCount() > 1);
        }
        assertTrue(stage + " title remains at most two visible lines", holder.title.getLayout()
                .getLineCount() <= 2);

        final View[] content = {holder.thumbnail, holder.title, holder.details,
                holder.handle, holder.menu};
        final String[] names = {"thumbnail", "title", "details", "handle", "menu"};
        for (int first = 0; first < content.length; first++) {
            final Rect bounds = boundsInRow(row, content[first]);
            assertInside(stage + " " + names[first], row, bounds);
            for (int second = first + 1; second < content.length; second++) {
                assertFalse(stage + " " + names[first] + " overlaps " + names[second],
                        Rect.intersects(bounds, boundsInRow(row, content[second])));
            }
        }
        assertInside(stage + " playback root", row, boundsInRow(row, holder.root));
        assertFalse(stage + " playback and overflow targets overlap", Rect.intersects(
                boundsInRow(row, holder.root), boundsInRow(row, holder.menu)));
        assertTarget(stage + " playback", holder.root);
        assertTarget(stage + " reorder", holder.handle);
        assertTarget(stage + " overflow", holder.menu);

        final boolean active = entry.state == OfflineStore.State.IMPORTING
                || entry.state == OfflineStore.State.DOWNLOADING;
        assertEquals(stage + " progress visibility", active ? View.VISIBLE : View.GONE,
                holder.progress.getVisibility());
        if (active) {
            final Rect progress = boundsInRow(row, holder.progress);
            assertInside(stage + " progress", row, progress);
            assertFalse(stage + " progress overlaps playback root", Rect.intersects(progress,
                    boundsInRow(row, holder.root)));
            assertFalse(stage + " progress overlaps overflow", Rect.intersects(progress,
                    boundsInRow(row, holder.menu)));
        }
        return row.getHeight();
    }

    private Context configuredContext(final int widthDp, final float fontScale) {
        final Configuration configuration = new Configuration(targetContext.getResources()
                .getConfiguration());
        configuration.fontScale = fontScale;
        configuration.screenWidthDp = widthDp;
        configuration.screenHeightDp = 640;
        configuration.smallestScreenWidthDp = widthDp;
        configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
        configuration.screenLayout = (configuration.screenLayout
                & ~Configuration.SCREENLAYOUT_SIZE_MASK) | Configuration.SCREENLAYOUT_SIZE_NORMAL;
        configuration.setLocale(Locale.ENGLISH);
        configuration.uiMode = Configuration.UI_MODE_TYPE_NORMAL
                | Configuration.UI_MODE_NIGHT_NO;
        // Use the app's complete existing shelf-compatible theme, not its OpeningTheme.
        return new ContextThemeWrapper(targetContext.createConfigurationContext(configuration),
                R.style.LightTheme);
    }

    private static void assertTextFits(final String stage, final TextView text) {
        final Layout layout = text.getLayout();
        assertNotNull(stage + " must have a real Android text Layout", layout);
        assertTrue(stage + " must render nonempty text", text.length() > 0);
        assertTrue(stage + " must have usable width", text.getWidth()
                - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() > 0);
        final int availableHeight = text.getHeight() - text.getCompoundPaddingTop()
                - text.getCompoundPaddingBottom();
        assertTrue(stage + " text Layout height=" + layout.getHeight()
                + " exceeds content height=" + availableHeight,
                layout.getHeight() <= availableHeight);
        // Horizontal ellipsis is approved: do not require every character to be displayed.
    }

    private static Rect boundsInRow(final ViewGroup row, final View child) {
        final Rect bounds = new Rect(0, 0, child.getWidth(), child.getHeight());
        row.offsetDescendantRectToMyCoords(child, bounds);
        return bounds;
    }

    private static void assertInside(final String stage, final View row, final Rect bounds) {
        assertTrue(stage + " must have nonempty bounds: " + bounds,
                bounds.width() > 0 && bounds.height() > 0);
        assertTrue(stage + " outside row " + row.getWidth() + "x" + row.getHeight()
                + ": " + bounds, bounds.left >= 0 && bounds.top >= 0
                && bounds.right <= row.getWidth() && bounds.bottom <= row.getHeight());
    }

    private static void assertTarget(final String stage, final View target) {
        final int minimum = dp(target.getContext(), 48);
        assertTrue(stage + " target width=" + target.getWidth() + " requires " + minimum,
                target.getWidth() >= minimum);
        assertTrue(stage + " target height=" + target.getHeight() + " requires " + minimum,
                target.getHeight() >= minimum);
    }

    private static int dp(final Context context, final int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class LayoutListener implements OfflineShelfAdapter.Listener {
        private final Context context;

        LayoutListener(final Context context) {
            this.context = context;
        }

        @Override public void play(final OfflineStore.Entry entry) {
            fail("layout fixture must not initiate playback");
        }

        @Override public void menu(final OfflineStore.Entry entry, final View anchor,
                                   final boolean movementOnly) {
            fail("layout fixture must not open management UI");
        }

        @Override public void startDrag(final OfflineShelfAdapter.Holder holder) {
            fail("layout fixture must not initiate drag");
        }

        @Override public void move(final OfflineStore.Entry entry, final int offset) {
            fail("layout fixture must not persist a shelf move");
        }

        @Override public OfflineShelfAdapter.RowDetails details(final OfflineStore.Entry entry) {
            // Representative shelf resource labels, without Activity/mission/service setup.
            final int label;
            final int progress;
            switch (entry.state) {
                case READY:
                    label = R.string.offline_ready;
                    progress = -1;
                    break;
                case IMPORTING:
                    label = R.string.offline_copying;
                    progress = 37;
                    break;
                case DOWNLOADING:
                    label = R.string.offline_recovery_wait;
                    progress = -2;
                    break;
                default:
                    label = R.string.offline_failed;
                    progress = -1;
                    break;
            }
            return new OfflineShelfAdapter.RowDetails(context.getString(label) + " · "
                    + context.getString(R.string.offline_size, entry.bytes / 1_000_000.0),
                    progress);
        }
    }
}
