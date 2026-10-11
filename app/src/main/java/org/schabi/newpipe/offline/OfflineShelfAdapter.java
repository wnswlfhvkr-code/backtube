package org.schabi.newpipe.offline;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.DiffUtil;

import org.schabi.newpipe.R;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/** Reuses play-queue rows without its online image loader or mutable player queue. */
final class OfflineShelfAdapter extends RecyclerView.Adapter<OfflineShelfAdapter.Holder> {
    private final OfflineShelfOrder order = new OfflineShelfOrder(List.of());
    private final Listener listener;
    private boolean interactionEnabled = true;
    private String draggedId;
    private String currentUrl;

    OfflineShelfAdapter(final Listener listener) {
        this.listener = listener;
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    void setEntries(final List<OfflineStore.Entry> entries) {
        final List<OfflineStore.Entry> previous = new ArrayList<>(order.items());
        if (order.replace(entries)) {
            calculateUpdates(previous, order.items()).dispatchUpdatesTo(this);
        }
    }

    void completeMove(final List<OfflineStore.Entry> entries) {
        final List<OfflineStore.Entry> previous = new ArrayList<>(order.items());
        order.completeMove(entries);
        calculateUpdates(previous, order.items()).dispatchUpdatesTo(this);
    }

    void setCurrentUrl(@Nullable final String url) {
        if (!Objects.equals(currentUrl, url)) {
            currentUrl = url;
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    void setInteractionEnabled(final boolean enabled) {
        if (interactionEnabled != enabled) {
            interactionEnabled = enabled;
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    int positionOf(final String id) {
        for (int i = 0; i < getItemCount(); i++) {
            if (order.items().get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    boolean beginDrag(final Holder holder) {
        final OfflineStore.Entry entry = boundEntry(holder);
        if (entry == null || !interactionEnabled || !order.beginDrag(entry.id)) {
            return false;
        }
        draggedId = entry.id;
        return true;
    }

    boolean moveDrag(final int from, final int to) {
        if (from < 0 || from >= getItemCount()
                || !order.items().get(from).id.equals(draggedId) || !order.moveTo(to)) {
            return false;
        }
        notifyItemMoved(from, to);
        return true;
    }

    OfflineShelfOrder.Move finishDrag() {
        draggedId = null;
        return order.finishDrag();
    }

    void cancelDrag() {
        final List<OfflineStore.Entry> previous = new ArrayList<>(order.items());
        draggedId = null;
        order.cancelDrag();
        calculateUpdates(previous, order.items()).dispatchUpdatesTo(this);
    }

    static DiffUtil.DiffResult calculateUpdates(final List<OfflineStore.Entry> previous,
                                                final List<OfflineStore.Entry> next) {
        return DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() {
                return previous.size();
            }

            @Override public int getNewListSize() {
                return next.size();
            }

            @Override public boolean areItemsTheSame(final int oldPosition, final int newPosition) {
                return previous.get(oldPosition).id.equals(next.get(newPosition).id);
            }

            @Override public boolean areContentsTheSame(final int oldPosition,
                                                         final int newPosition) {
                // Progress lives outside Entry; positional move actions also need rebinding.
                return false;
            }
        });
    }

    OfflineShelfOrder.Move moveBy(final String id, final int offset) {
        return order.moveBy(id, offset);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final Holder holder = new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.offline_shelf_item, parent, false));
        installHandleTouch(holder);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder holder, final int position) {
        final OfflineStore.Entry entry = order.items().get(position);
        holder.boundId = entry.id;
        holder.itemView.setTag(entry.id);
        holder.title.setText(entry.title);
        holder.title.setSingleLine(false);
        holder.title.setMaxLines(2);
        holder.thumbnail.setImageResource(R.drawable.placeholder_thumbnail_video);
        holder.thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        holder.itemView.findViewById(R.id.itemDurationView).setVisibility(View.GONE);
        final RowDetails details = listener.details(entry);
        holder.details.setText(details.label);
        holder.progress.setVisibility(details.progress == -1 ? View.GONE : View.VISIBLE);
        holder.progress.setIndeterminate(details.progress == -2);
        if (details.progress >= 0) {
            holder.progress.setProgress(details.progress);
        }
        final boolean current = entry.localUrl().equals(currentUrl);
        holder.root.setSelected(current);
        holder.root.setContentDescription(entry.title + " · " + details.label
                + (current ? " · " + holder.root.getContext()
                .getString(R.string.offline_current_item) : ""));
        holder.root.setEnabled(interactionEnabled);
        holder.handle.setEnabled(interactionEnabled);
        holder.menu.setEnabled(interactionEnabled);
        holder.handle.setMinimumWidth(dp(holder.handle, 48));
        holder.handle.setMinimumHeight(dp(holder.handle, 48));
        holder.handle.setClickable(true);
        holder.handle.setFocusable(true);
        holder.handle.setContentDescription(holder.root.getContext()
                .getString(R.string.offline_reorder_description, entry.title));
        holder.menu.setContentDescription(holder.root.getContext()
                .getString(R.string.offline_item_menu_description, entry.title));

        holder.root.setOnClickListener(view -> {
            final OfflineStore.Entry selected = boundEntry(holder);
            if (interactionEnabled && draggedId == null && selected != null
                    && selected.state == OfflineStore.State.READY) {
                listener.play(selected);
            }
        });
        ViewCompat.replaceAccessibilityAction(holder.root, AccessibilityActionCompat.ACTION_CLICK,
                holder.root.getContext().getString(R.string.offline_play), null);
        holder.root.setOnLongClickListener(view -> showMenu(holder, holder.menu, false));
        holder.menu.setOnClickListener(view -> showMenu(holder, view, false));
        holder.handle.setOnClickListener(view -> showMenu(holder, view, true));
        addMoveActions(holder, position);
    }

    private void installHandleTouch(final Holder holder) {
        // Installed once: rebinding a row must not forget an already accepted touch gesture.
        holder.handle.setOnTouchListener(new View.OnTouchListener() {
            private boolean touching;
            private boolean startedDrag;

            @Override public boolean onTouch(final View view, final MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touching = true;
                        startedDrag = false;
                        if (interactionEnabled && draggedId == null) {
                            listener.startDrag(holder);
                            startedDrag = draggedId != null;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (touching && !startedDrag) {
                            view.performClick();
                        }
                        touching = false;
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        touching = false;
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
    }

    private boolean showMenu(final Holder holder, final View anchor, final boolean movementOnly) {
        final OfflineStore.Entry entry = boundEntry(holder);
        if (!interactionEnabled || draggedId != null || entry == null) {
            return false;
        }
        listener.menu(entry, anchor, movementOnly);
        return true;
    }

    private void addMoveActions(final Holder holder, final int position) {
        ViewCompat.removeAccessibilityAction(holder.root, R.id.offline_move_up);
        ViewCompat.removeAccessibilityAction(holder.root, R.id.offline_move_down);
        if (!interactionEnabled) {
            return;
        }
        if (position > 0) {
            addMoveAction(holder, position, R.id.offline_move_up,
                    R.string.offline_move_up_description, -1);
        }
        if (position + 1 < getItemCount()) {
            addMoveAction(holder, position, R.id.offline_move_down,
                    R.string.offline_move_down_description, 1);
        }
    }

    private void addMoveAction(final Holder holder, final int position,
                               final int actionId, final int title,
                               final int offset) {
        final String label = holder.root.getContext().getString(title,
                holder.title.getText(), position + 1, getItemCount());
        ViewCompat.replaceAccessibilityAction(holder.root,
                new AccessibilityActionCompat(actionId, label), label, (view, arguments) -> {
                    final OfflineStore.Entry entry = boundEntry(holder);
                    if (!interactionEnabled || draggedId != null || entry == null) {
                        return false;
                    }
                    listener.move(entry, offset);
                    return true;
                });
    }

    @Nullable
    private OfflineStore.Entry boundEntry(final Holder holder) {
        final int index = holder.getBindingAdapterPosition();
        if (index < 0 || index >= getItemCount()) {
            return null;
        }
        final OfflineStore.Entry entry = order.items().get(index);
        return entry.id.equals(holder.boundId) ? entry : null;
    }

    @Override
    public int getItemCount() {
        return order.items().size();
    }

    private static int dp(final View view, final int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    interface Listener {
        void play(OfflineStore.Entry entry);
        void menu(OfflineStore.Entry entry, View anchor, boolean movementOnly);
        void startDrag(Holder holder);
        void move(OfflineStore.Entry entry, int offset);
        RowDetails details(OfflineStore.Entry entry);
    }

    static final class RowDetails {
        final String label;
        /** -1: hidden; -2: indeterminate; otherwise a percentage. */
        final int progress;

        RowDetails(final String label, final int progress) {
            this.label = label;
            this.progress = progress;
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View root;
        final TextView title;
        final TextView details;
        final ImageView thumbnail;
        final View handle;
        final View menu;
        final ProgressBar progress;
        String boundId;

        Holder(final View view) {
            super(view);
            root = view.findViewById(R.id.itemRoot);
            title = view.findViewById(R.id.itemVideoTitleView);
            details = view.findViewById(R.id.itemAdditionalDetails);
            thumbnail = view.findViewById(R.id.itemThumbnailView);
            handle = view.findViewById(R.id.itemHandle);
            menu = view.findViewById(R.id.offline_item_menu);
            progress = view.findViewById(R.id.offline_item_progress);
        }
    }
}
