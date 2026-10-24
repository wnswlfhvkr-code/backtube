package org.schabi.newpipe.info_list.holder;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.ktx.ViewUtils;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.StreamTypeUtil;
import org.schabi.newpipe.util.image.CoilHelper;
import org.schabi.newpipe.views.AnimatedProgressBar;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.SerialDisposable;

public class StreamMiniInfoItemHolder extends InfoItemHolder {
    public final ImageView itemThumbnailView;
    public final TextView itemVideoTitleView;
    public final TextView itemUploaderView;
    public final TextView itemDurationView;
    private final AnimatedProgressBar itemProgressView;
    private SerialDisposable progressStateDisposable;
    private long progressStateGeneration;
    private boolean progressStateLoaded;
    private boolean progressStateAttached;
    private boolean progressStateFromUpdate;
    private String boundStreamKey;
    private StreamInfoItem boundStreamItem;
    private HistoryRecordManager boundHistoryRecordManager;

    StreamMiniInfoItemHolder(final InfoItemBuilder infoItemBuilder, final int layoutId,
                             final ViewGroup parent) {
        super(infoItemBuilder, layoutId, parent);

        itemThumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        itemVideoTitleView = itemView.findViewById(R.id.itemVideoTitleView);
        itemUploaderView = itemView.findViewById(R.id.itemUploaderView);
        itemDurationView = itemView.findViewById(R.id.itemDurationView);
        itemProgressView = itemView.findViewById(R.id.itemProgressView);
        // Removing the whole RecyclerView from its window need not dispatch adapter row
        // callbacks. Also observe the real View lifetime; duplicate attach signals are guarded.
        itemView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(final View view) {
                onHolderAttached();
            }

            @Override
            public void onViewDetachedFromWindow(final View view) {
                onHolderDetached();
            }
        });
    }

    public StreamMiniInfoItemHolder(final InfoItemBuilder infoItemBuilder, final ViewGroup parent) {
        this(infoItemBuilder, R.layout.list_stream_mini_item, parent);
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        cancelProgressStateLoad();
        progressStateFromUpdate = false;
        if (!(infoItem instanceof StreamInfoItem)) {
            boundStreamKey = null;
            boundStreamItem = null;
            boundHistoryRecordManager = null;
            progressStateLoaded = false;
            resetProgressView();
            return;
        }
        final StreamInfoItem item = (StreamInfoItem) infoItem;
        boundStreamItem = item;
        boundHistoryRecordManager = historyRecordManager;
        boundStreamKey = streamKey(item);
        resetProgressView();
        progressStateLoaded = false;

        itemVideoTitleView.setText(item.getName());
        itemUploaderView.setText(item.getUploaderName());

        if (item.getDuration() > 0) {
            itemDurationView.setText(Localization.getDurationString(item.getDuration()));
            itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.getContext(),
                    R.color.duration_background_color));
            itemDurationView.setVisibility(View.VISIBLE);

            if (progressStateAttached && DependentPreferenceHelper
                    .getPositionsInListsEnabled(itemProgressView.getContext())) {
                loadProgressState(item, historyRecordManager, false, false);
            }
        } else if (StreamTypeUtil.isLiveStream(item.getStreamType())) {
            itemDurationView.setText(R.string.duration_live);
            itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.getContext(),
                    R.color.live_duration_background_color));
            itemDurationView.setVisibility(View.VISIBLE);
            itemProgressView.setVisibility(View.GONE);
        } else {
            itemDurationView.setVisibility(View.GONE);
            itemProgressView.setVisibility(View.GONE);
        }

        // Default thumbnail is shown on error, while loading and if the url is empty
        CoilHelper.INSTANCE.loadThumbnail(itemThumbnailView, item.getThumbnails());

        itemView.setOnClickListener(view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().selected(item);
            }
        });

        switch (item.getStreamType()) {
            case AUDIO_STREAM:
            case VIDEO_STREAM:
            case LIVE_STREAM:
            case AUDIO_LIVE_STREAM:
            case POST_LIVE_STREAM:
            case POST_LIVE_AUDIO_STREAM:
                enableLongClick(item);
                break;
            case NONE:
            default:
                disableLongClick();
                break;
        }
    }

    @Override
    public void updateState(final InfoItem infoItem,
                            final HistoryRecordManager historyRecordManager) {
        if (!(infoItem instanceof StreamInfoItem)) {
            cancelProgressStateLoad();
            boundStreamKey = null;
            boundStreamItem = null;
            boundHistoryRecordManager = null;
            progressStateLoaded = false;
            resetProgressView();
            return;
        }

        final StreamInfoItem item = (StreamInfoItem) infoItem;
        final String key = streamKey(item);
        if (!key.equals(boundStreamKey)) {
            return;
        }
        progressStateFromUpdate = true;

        // A same-key update may carry a fresh item instance or manager. Keep those references
        // current so detach/attach cannot reload an obsolete item or manager.
        boundStreamItem = item;
        boundHistoryRecordManager = historyRecordManager;

        if (item.getDuration() <= 0 || StreamTypeUtil.isLiveStream(item.getStreamType())
                || !DependentPreferenceHelper
                .getPositionsInListsEnabled(itemProgressView.getContext())) {
            cancelProgressStateLoad();
            progressStateLoaded = true;
            resetProgressView();
            return;
        }

        // A history update can follow the read's snapshot, even for the same manager/item.
        // Supersede the pending read; detached rows defer their latest request until attach.
        if (!progressStateAttached) {
            progressStateLoaded = false;
            return;
        }

        loadProgressState(item, historyRecordManager, true, true);
    }

    public void cancelPendingStateLoad() {
        progressStateAttached = false;
        progressStateFromUpdate = false;
        cancelProgressStateLoad();
        boundStreamKey = null;
        boundStreamItem = null;
        boundHistoryRecordManager = null;
        progressStateLoaded = false;
        resetProgressView();
    }

    public void onHolderDetached() {
        progressStateAttached = false;
        cancelProgressStateLoad();
        progressStateLoaded = false;
        resetProgressView();
    }

    public void onHolderAttached() {
        final boolean wasDetached = !progressStateAttached;
        progressStateAttached = true;
        if (wasDetached) {
            progressStateLoaded = false;
        }
        if (boundStreamItem == null || boundHistoryRecordManager == null
                || boundStreamKey == null) {
            return;
        }
        if (DependentPreferenceHelper
                .getPositionsInListsEnabled(itemProgressView.getContext())
                && boundStreamItem.getDuration() > 0
                && (!progressStateFromUpdate
                || !StreamTypeUtil.isLiveStream(boundStreamItem.getStreamType()))
                && progressStateDisposable == null && !progressStateLoaded) {
            loadProgressState(boundStreamItem, boundHistoryRecordManager, false, false);
        }
    }

    private void loadProgressState(final StreamInfoItem item,
                                   final HistoryRecordManager historyRecordManager,
                                   final boolean animate,
                                   final boolean preserveProgressUntilResult) {
        // Every actual request start is latest-request-wins: cancel the old request and advance
        // the generation before capturing the new generation and request identity.
        cancelProgressStateLoad();
        final long generation = progressStateGeneration;
        final String key = streamKey(item);
        final SerialDisposable request = new SerialDisposable();
        progressStateDisposable = request;
        progressStateLoaded = false;
        if (!preserveProgressUntilResult) {
            resetProgressView();
        }

        request.set(historyRecordManager.loadStreamState(item)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(stateArray -> {
                    if (!isCurrentRequest(request, generation, key, historyRecordManager)) {
                        return;
                    }
                    // Only the request that still owns the field may clear it.
                    progressStateDisposable = null;
                    progressStateLoaded = true;
                    final StreamStateEntity state = stateArray != null && stateArray.length > 0
                            ? stateArray[0] : null;
                    applyProgressState(boundStreamItem, state, animate);
                }, error -> {
                    if (!isCurrentRequest(request, generation, key, historyRecordManager)) {
                        return;
                    }
                    progressStateDisposable = null;
                    progressStateLoaded = true;
                    resetProgressView();
                }));
    }

    private boolean isCurrentRequest(final SerialDisposable request,
                                     final long generation,
                                     final String key,
                                     final HistoryRecordManager historyRecordManager) {
        return progressStateAttached && boundStreamItem != null
                && key.equals(streamKey(boundStreamItem))
                && generation == progressStateGeneration
                && request == progressStateDisposable
                && key.equals(boundStreamKey)
                && historyRecordManager == boundHistoryRecordManager;
    }

    private void cancelProgressStateLoad() {
        // Disposing alone is insufficient when a result has already been posted to main.
        progressStateGeneration++;
        if (progressStateDisposable != null) {
            progressStateDisposable.dispose();
            progressStateDisposable = null;
        }
    }

    private void resetProgressView() {
        // ViewUtils uses a property animator, distinct from AnimatedProgressBar's Animation.
        // Remove its end listener before cancelling so it cannot hide a rebound row later.
        itemProgressView.animate().setListener(null).cancel();
        final Animation animation = itemProgressView.getAnimation();
        if (animation != null) {
            animation.cancel();
        }
        itemProgressView.clearAnimation();
        itemProgressView.setAlpha(1f);
        itemProgressView.setVisibility(View.GONE);
        itemProgressView.setProgress(0);
    }

    private void applyProgressState(final StreamInfoItem item,
                                    final StreamStateEntity state,
                                    final boolean animate) {
        if (state == null || item.getDuration() <= 0
                // Reloading on attach has no animation, but retains the latest display rule.
                || (progressStateFromUpdate && StreamTypeUtil.isLiveStream(item.getStreamType()))
                || !DependentPreferenceHelper
                .getPositionsInListsEnabled(itemProgressView.getContext())) {
            resetProgressView();
            return;
        }
        itemProgressView.setMax((int) item.getDuration());
        final int progress = (int) TimeUnit.MILLISECONDS.toSeconds(state.getProgressMillis());
        if (animate && itemProgressView.getVisibility() == View.VISIBLE) {
            itemProgressView.setProgressAnimated(progress);
        } else {
            itemProgressView.setProgress(progress);
            if (animate) {
                ViewUtils.animate(itemProgressView, true, 500);
            } else {
                itemProgressView.setVisibility(View.VISIBLE);
            }
        }
    }

    private static String streamKey(final StreamInfoItem item) {
        return item.getServiceId() + ":" + item.getUrl();
    }

    private void enableLongClick(final StreamInfoItem item) {
        itemView.setLongClickable(true);
        itemView.setOnLongClickListener(view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().held(item);
            }
            return true;
        });
    }

    private void disableLongClick() {
        itemView.setLongClickable(false);
        itemView.setOnLongClickListener(null);
    }
}
