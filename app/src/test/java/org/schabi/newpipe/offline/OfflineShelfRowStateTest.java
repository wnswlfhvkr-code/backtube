package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import org.mockito.Answers;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM contract fixture for the real binder and durable store, not a TalkBack/device test.
 * Android views and accessibility action storage are replaced at the platform boundary.
 */
public class OfflineShelfRowStateTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    // Collect both semantic failures and continue through retry/restoration on the RED binder.
    @Rule public ErrorCollector errors = new ErrorCollector();

    private final RecordingListener listener = new RecordingListener();
    private final Map<View, Map<Integer, CharSequence>> actions = new IdentityHashMap<>();
    private MockedConstruction<?> adapterObservables;
    private MockedStatic<ViewCompat> viewCompat;
    private OfflineStore store;
    private OfflineShelfAdapter adapter;
    private OfflineShelfAdapter.Holder holder;
    private ViewState row;
    private ViewState menu;
    private ViewState handle;
    private String savedId;

    @Before public void setUp() throws Exception {
        viewCompat = mockStatic(ViewCompat.class, invocation -> {
            final String method = invocation.getMethod().getName();
            if (method.equals("replaceAccessibilityAction")) {
                final View view = invocation.getArgument(0);
                final AccessibilityActionCompat action = invocation.getArgument(1);
                final CharSequence label = invocation.getArgument(2);
                // Use Compat's constant object identity: getId delegates to mockable Android.
                // One row has no custom move action; any unexpected action is a setup error.
                assertTrue("fixture only models playback action", action
                        == AccessibilityActionCompat.ACTION_CLICK);
                actions.computeIfAbsent(view, ignored -> new HashMap<>())
                        .put(AccessibilityNodeInfoCompat.ACTION_CLICK, label);
                return null;
            }
            if (method.equals("removeAccessibilityAction")) {
                actions.computeIfAbsent(invocation.getArgument(0), ignored -> new HashMap<>())
                        .remove((Integer) invocation.getArgument(1));
                return null;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        // android.database.Observable's mockable constructor leaves mObservers null.
        // Only RecyclerView notification delivery is replaced; ordering/binding remain real.
        adapterObservables = mockConstruction(Class.forName(
                "androidx.recyclerview.widget.RecyclerView$AdapterDataObservable"));

        final Context context = mock(Context.class);
        final Resources resources = mock(Resources.class);
        final DisplayMetrics metrics = mock(DisplayMetrics.class);
        metrics.density = 1.0f;
        when(resources.getDisplayMetrics()).thenReturn(metrics);
        when(context.getString(anyInt())).thenAnswer(invocation -> {
            final int id = invocation.getArgument(0);
            return id == R.string.offline_play ? "Play" : "Current item";
        });
        when(context.getString(anyInt(), any(Object[].class)))
                .thenReturn("Management action for saved item");

        // Initial root flags mirror play_queue_item.xml (clickable and focusable).
        row = new ViewState(context, resources, true, true);
        menu = new ViewState(context, resources, false, true);
        handle = new ViewState(context, resources, false, false);
        final View itemView = mock(View.class);
        final TextView title = mock(TextView.class);
        final TextView details = mock(TextView.class);
        final ImageView thumbnail = mock(ImageView.class);
        final ProgressBar progress = mock(ProgressBar.class);
        final View duration = mock(View.class);
        when(title.getText()).thenReturn("Saved recording");
        when(itemView.findViewById(R.id.itemRoot)).thenReturn(row.view);
        when(itemView.findViewById(R.id.itemVideoTitleView)).thenReturn(title);
        when(itemView.findViewById(R.id.itemAdditionalDetails)).thenReturn(details);
        when(itemView.findViewById(R.id.itemThumbnailView)).thenReturn(thumbnail);
        when(itemView.findViewById(R.id.itemHandle)).thenReturn(handle.view);
        when(itemView.findViewById(R.id.offline_item_menu)).thenReturn(menu.view);
        when(itemView.findViewById(R.id.offline_item_progress)).thenReturn(progress);
        when(itemView.findViewById(R.id.itemDurationView)).thenReturn(duration);
        holder = spy(new OfflineShelfAdapter.Holder(itemView));
        // There is no attached RecyclerView in this JVM fixture. Bind a real holder at row 0.
        doReturn(0).when(holder).getBindingAdapterPosition();
        adapter = new OfflineShelfAdapter(listener);

        store = new OfflineStore(temporary.newFolder(), 1024);
        final OfflineStore.Entry saved = store.create("Saved recording", "owned:recording", 0,
                "content://fixture/recording", "audio/wav");
        savedId = saved.id;
        store.copy(savedId, new ByteArrayInputStream(new byte[32]));
    }

    @After public void tearDown() {
        if (adapterObservables != null) {
            adapterObservables.close();
        }
        if (viewCompat != null) {
            viewCompat.close();
        }
    }

    @Test public void recycledRowTracksRealMissingFileFailureRetryAndCompletion()
            throws Exception {
        bindAndCheck(OfflineStore.State.READY, true);
        assertTrue("fixture must remove actual saved bytes",
                store.file(store.get(savedId)).delete());
        // list/get performs the actual READY -> FAILED transition after discovering missing bytes.
        bindAndCheck(OfflineStore.State.FAILED, true);
        store.prepareRetry(savedId);
        bindAndCheck(OfflineStore.State.IMPORTING, true);
        store.copy(savedId, new ByteArrayInputStream(new byte[40]));
        bindAndCheck(OfflineStore.State.READY, true);
        assertEquals(40, store.file(store.get(savedId)).length());
    }

    @Test public void recycledReadyRowRemovesPlaySemanticsDuringInteractionLock()
            throws Exception {
        bindAndCheck(OfflineStore.State.READY, true);
        adapter.setInteractionEnabled(false);
        bindAndCheck(OfflineStore.State.READY, false);
        adapter.setInteractionEnabled(true);
        bindAndCheck(OfflineStore.State.READY, true);
    }

    private void bindAndCheck(final OfflineStore.State state, final boolean interactionEnabled)
            throws Exception {
        adapter.setEntries(store.list());
        adapter.onBindViewHolder(holder, 0);
        final OfflineStore.Entry entry = store.get(savedId);
        final String stage = state + "/interaction=" + interactionEnabled;
        assertEquals(stage, state, entry.state);
        assertEquals(stage, savedId, holder.boundId);
        assertEquals(stage, "Saved recording · " + state, row.description);

        final boolean playable = interactionEnabled && state == OfflineStore.State.READY;
        // Assert captured semantic state, rather than whether a setter was called.
        // Ordering matters: setOnClickListener(non-null) makes an Android View clickable again.
        errors.checkSucceeds(() -> {
            assertEquals(stage + " row clickability", playable, row.view.isClickable());
            return null;
        });
        errors.checkSucceeds(() -> {
            assertEquals(stage + " advertised play action", playable,
                    actionMap().containsKey(AccessibilityNodeInfoCompat.ACTION_CLICK));
            return null;
        });
        if (playable) {
            assertEquals(stage, "Play", actionMap()
                    .get(AccessibilityNodeInfoCompat.ACTION_CLICK));
        }

        // Invoke the real listener even when semantics say it should not be offered.
        // This distinguishes the existing playback guard from the advertised-state regression.
        final int played = listener.played.size();
        assertNotNull(stage, row.click);
        row.click.onClick(row.view);
        assertEquals(stage + " playback guard", played + (playable ? 1 : 0),
                listener.played.size());
        if (playable) {
            assertEquals(savedId, listener.played.get(played));
        }
        checkManagement(stage, interactionEnabled);
    }

    private void checkManagement(final String stage, final boolean interactionEnabled) {
        assertEquals(stage, interactionEnabled, row.view.isEnabled());
        assertTrue(stage + " keeps row focus", row.view.isFocusable());
        assertTrue(stage + " keeps long click", row.view.isLongClickable());
        assertEquals(stage, interactionEnabled, menu.view.isEnabled());
        assertEquals(stage, interactionEnabled, handle.view.isEnabled());
        assertTrue(stage + " handle remains focusable", handle.view.isFocusable());
        assertTrue(stage + " handle remains clickable", handle.view.isClickable());

        final int menus = listener.menus.size();
        // Invoke binder-installed listeners directly so the real interaction guard decides.
        // View.performClick does not itself model input dispatch's enabled/clickable checks.
        assertNotNull(stage, row.longClick);
        assertNotNull(stage, menu.click);
        assertNotNull(stage, handle.click);
        assertEquals(stage, interactionEnabled, row.longClick.onLongClick(row.view));
        menu.click.onClick(menu.view);
        handle.click.onClick(handle.view);
        assertEquals(stage, menus + (interactionEnabled ? 3 : 0), listener.menus.size());
        if (interactionEnabled) {
            checkMenu(listener.menus.get(menus), menu.view, false);
            checkMenu(listener.menus.get(menus + 1), menu.view, false);
            checkMenu(listener.menus.get(menus + 2), handle.view, true);
        }
        assertFalse(stage + " first row cannot move up",
                actionMap().containsKey(R.id.offline_move_up));
        assertFalse(stage + " only row cannot move down",
                actionMap().containsKey(R.id.offline_move_down));
    }

    private void checkMenu(final MenuRequest request, final View anchor,
                           final boolean movementOnly) {
        assertEquals(savedId, request.id);
        assertSame(anchor, request.anchor);
        assertEquals(movementOnly, request.movementOnly);
    }

    private Map<Integer, CharSequence> actionMap() {
        return actions.computeIfAbsent(row.view, ignored -> new HashMap<>());
    }

    private static final class MenuRequest {
        private final String id;
        private final View anchor;
        private final boolean movementOnly;

        MenuRequest(final String id, final View anchor, final boolean movementOnly) {
            this.id = id;
            this.anchor = anchor;
            this.movementOnly = movementOnly;
        }
    }

    private static final class RecordingListener implements OfflineShelfAdapter.Listener {
        private final List<String> played = new ArrayList<>();
        private final List<MenuRequest> menus = new ArrayList<>();

        @Override public void play(final OfflineStore.Entry entry) {
            played.add(entry.id);
        }

        @Override public void menu(final OfflineStore.Entry entry, final View anchor,
                                   final boolean movementOnly) {
            menus.add(new MenuRequest(entry.id, anchor, movementOnly));
        }

        @Override public void startDrag(final OfflineShelfAdapter.Holder ignored) { }

        @Override public void move(final OfflineStore.Entry entry, final int offset) { }

        @Override public OfflineShelfAdapter.RowDetails details(final OfflineStore.Entry entry) {
            return new OfflineShelfAdapter.RowDetails(entry.state.name(),
                    entry.state == OfflineStore.State.IMPORTING ? -2 : -1);
        }
    }

    /** Explicit model of relevant View flags and listener installation side effects. */
    private static final class ViewState {
        private final View view = mock(View.class);
        private boolean clickable;
        private boolean focusable;
        private boolean enabled = true;
        private boolean longClickable;
        private CharSequence description;
        private View.OnClickListener click;
        private View.OnLongClickListener longClick;

        ViewState(final Context context, final Resources resources, final boolean clickable,
                  final boolean focusable) {
            this.clickable = clickable;
            this.focusable = focusable;
            when(view.getContext()).thenReturn(context);
            when(view.getResources()).thenReturn(resources);
            when(view.isClickable()).thenAnswer(ignored -> this.clickable);
            when(view.isFocusable()).thenAnswer(ignored -> this.focusable);
            when(view.isEnabled()).thenAnswer(ignored -> enabled);
            when(view.isLongClickable()).thenAnswer(ignored -> longClickable);
            doAnswer(invocation -> {
                this.clickable = invocation.getArgument(0);
                return null;
            }).when(view).setClickable(anyBoolean());
            doAnswer(invocation -> {
                this.focusable = invocation.getArgument(0);
                return null;
            }).when(view).setFocusable(anyBoolean());
            doAnswer(invocation -> {
                enabled = invocation.getArgument(0);
                return null;
            }).when(view).setEnabled(anyBoolean());
            doAnswer(invocation -> {
                longClickable = invocation.getArgument(0);
                return null;
            }).when(view).setLongClickable(anyBoolean());
            doAnswer(invocation -> {
                description = invocation.getArgument(0);
                return null;
            }).when(view).setContentDescription(any());
            doAnswer(invocation -> {
                click = invocation.getArgument(0);
                if (click != null) {
                    this.clickable = true;
                }
                return null;
            }).when(view).setOnClickListener(any());
            doAnswer(invocation -> {
                longClick = invocation.getArgument(0);
                if (longClick != null) {
                    longClickable = true;
                }
                return null;
            }).when(view).setOnLongClickListener(any());
        }
    }
}
