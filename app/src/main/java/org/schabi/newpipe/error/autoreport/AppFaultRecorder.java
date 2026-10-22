package org.schabi.newpipe.error.autoreport;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/** Best-effort local capture. A reporting failure must never become another app failure. */
public final class AppFaultRecorder {
    private AppFaultRecorder() {
    }

    public static boolean record(final String trace, final int version, final int api,
                                 final File directory, final long now) {
        try {
            final Optional<SanitizedAppFault> fault =
                    SanitizedAppFault.fromAcra(trace, version, api);
            return fault.isPresent() && new AppFaultOutbox(directory).enqueue(fault.get(), now)
                    == AppFaultOutbox.Result.ADDED;
        } catch (final IOException | RuntimeException ignored) {
            // Do not log raw input, recurse into ACRA, or reset a damaged quota ledger.
            return false;
        }
    }
}
