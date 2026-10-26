package org.schabi.newpipe.download;

import android.app.Activity;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.schabi.newpipe.R;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** A confirmation for one request, never a persistent network preference. */
public final class MobileDataDownloadConfirmation {
    private MobileDataDownloadConfirmation() { }

    public static void request(final Activity activity, final Consumer<Boolean> accepted,
                               final Runnable declined) {
        request(activity, DownloadNetworkPolicy.current(activity), accepted, declined);
    }

    static AlertDialog request(final Activity activity, final DownloadNetworkPolicy.Type network,
                               final Consumer<Boolean> accepted, final Runnable declined) {
        return request(activity, network, () -> DownloadNetworkPolicy.current(activity),
                accepted, declined);
    }

    static AlertDialog request(final Activity activity, final DownloadNetworkPolicy.Type network,
                               final Supplier<DownloadNetworkPolicy.Type> currentNetwork,
                               final Consumer<Boolean> accepted, final Runnable declined) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            declined.run();
            return null;
        }
        if (network == DownloadNetworkPolicy.Type.WIFI) {
            accepted.accept(false);
        } else if (network == DownloadNetworkPolicy.Type.MOBILE) {
            return new AlertDialog.Builder(activity)
                    .setMessage(R.string.mobile_data_download_confirm)
                    .setPositiveButton(R.string.download, (dialog, which) -> {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            declined.run();
                            return;
                        }
                        final DownloadNetworkPolicy.Type now = currentNetwork.get();
                        if (now == DownloadNetworkPolicy.Type.WIFI
                                || now == DownloadNetworkPolicy.Type.MOBILE) {
                            accepted.accept(true);
                        } else {
                            showUnavailable(activity, now);
                            declined.run();
                        }
                    })
                    .setNegativeButton(R.string.cancel, (dialog, which) -> declined.run())
                    .setOnCancelListener(dialog -> declined.run())
                    .show();
        } else {
            showUnavailable(activity, network);
            declined.run();
        }
        return null;
    }

    private static void showUnavailable(final Activity activity,
                                        final DownloadNetworkPolicy.Type type) {
        Toast.makeText(activity, type == DownloadNetworkPolicy.Type.NONE
                        ? R.string.download_network_unavailable
                        : R.string.download_network_unsupported,
                Toast.LENGTH_LONG).show();
    }
}
