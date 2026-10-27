package org.schabi.newpipe.info_list.dialog;

import android.content.Context;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import org.schabi.newpipe.download.DownloadDialog;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.util.SparseItemUtil;

import java.util.Objects;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Opens the existing offline selector for the stream selected in a long-press menu. */
final class OfflineSaveMenuAction {
    private static final String DIALOG_TAG = "offline_save";

    private OfflineSaveMenuAction() { }

    static void open(final Fragment fragment, final StreamInfoItem item) {
        final Context context = fragment.getContext();
        final View requestView = fragment.getView();
        if (!fragment.isAdded() || context == null || requestView == null) {
            return;
        }
        final Context applicationContext = context.getApplicationContext();
        final String detailKey = fragment instanceof VideoDetailFragment
                ? ((VideoDetailFragment) fragment).getDetailSelectionKey() : null;
        SparseItemUtil.fetchStreamInfoAndSaveToDatabase(applicationContext,
                item.getServiceId(), item.getUrl(), info -> {
                    if (!ownsView(fragment, requestView, detailKey)) {
                        return;
                    }
                    Single.fromCallable(() -> DownloadDialog.allOfflineKindsSaved(
                                    applicationContext, info))
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(complete -> {
                                if (!ownsView(fragment, requestView, detailKey)) {
                                    return;
                                }
                                if (complete) {
                                    DownloadDialog.showAlreadySavedToast(applicationContext,
                                            null);
                                    return;
                                }
                                final FragmentManager manager = fragment.getChildFragmentManager();
                                if (!manager.isStateSaved()
                                        && manager.findFragmentByTag(DIALOG_TAG) == null) {
                                    DownloadDialog.forOffline(fragment.requireContext(), info)
                                            .showNow(manager, DIALOG_TAG);
                                }
                            }, error -> {
                                final ErrorInfo details = new ErrorInfo(error,
                                        UserAction.DOWNLOAD_OPEN_DIALOG,
                                        "Checking saved item", info);
                                if (ownsView(fragment, requestView, detailKey)) {
                                    ErrorUtil.showSnackbar(fragment.requireContext(), details);
                                } else {
                                    ErrorUtil.createNotification(applicationContext, details);
                                }
                            });
                });
    }

    private static boolean ownsView(final Fragment fragment, final View requestView,
                                    final String detailKey) {
        return fragment.isAdded() && fragment.getView() == requestView
                && fragment.getContext() != null
                && (!(fragment instanceof VideoDetailFragment)
                || Objects.equals(((VideoDetailFragment) fragment).getDetailSelectionKey(),
                        detailKey));
    }
}
