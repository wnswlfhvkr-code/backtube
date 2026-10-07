package org.schabi.newpipe.offline;

import java.io.IOException;
import java.util.concurrent.Callable;

/** Keeps local-only identities out of every network/extractor fallback. */
public final class OfflineFirst {
    private OfflineFirst() { }

    public static <T> T select(final Callable<T> local, final Callable<T> remote,
                               final boolean localOnly) throws Exception {
        try {
            final T saved = local.call();
            if (saved != null) {
                return saved;
            }
        } catch (final IOException error) {
            if (localOnly) {
                throw error;
            }
        }
        if (localOnly) {
            throw new IOException("Offline copy missing or expired");
        }
        return remote.call();
    }
}
