package org.schabi.newpipe.faultdiagnostic;

import org.schabi.newpipe.error.autoreport.AppFaultOutbox;
import org.schabi.newpipe.error.autoreport.SanitizedAppFault;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Pure, Android-free controller of the local synthetic fault probe.
 *
 * <p>It classifies one fixed, transient synthetic trace through the shared PR7 sanitizer and
 * stores the result in the shared PR7 outbox. Every operation uses a fresh outbox instance.
 * Failures are reported as a fixed {@code ERROR} status only: no exception text, no logging,
 * no reset and no repair of the stored ledger.</p>
 */
public final class LocalFaultProbe {
    public static final String ADDED = "ADDED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String FULL = "FULL";
    public static final String REJECTED = "REJECTED";
    public static final String ERROR = "ERROR";

    private static final String OUTBOX_FILE = "outbox.bin";
    private static final int MAX_SCAN_BYTES = 65536;

    /** Fixed fake trace. Never thrown, logged, displayed or persisted. */
    private static final String SYNTHETIC_TRACE = "java.lang.NullPointerException: "
            + "SYNTHETIC_TOKEN_MARKER SYNTHETIC_DEVICE_MARKER SYNTHETIC_PATH_MARKER "
            + "synthetic@example.invalid https://example.invalid/private rawstacktrace\n"
            + "\tat org.schabi.newpipe.player.SyntheticProbe.run(SyntheticProbe.java:42)\n";

    /** Fixed fake markers which must never appear in the persisted ledger. */
    private static final String[] SENSITIVE_MARKERS = {
            "SYNTHETIC_TOKEN_MARKER",
            "SYNTHETIC_DEVICE_MARKER",
            "SYNTHETIC_PATH_MARKER",
            "synthetic@example.invalid",
            "https://example.invalid/private",
            "example.invalid",
            "rawstacktrace",
            "NullPointerException",
            "org.schabi.newpipe.player",
            "SyntheticProbe",
    };

    private final File directory;
    private final int productVersion;
    private final int androidApi;

    public LocalFaultProbe(final File directory, final int productVersion,
                           final int androidApi) {
        this.directory = directory;
        this.productVersion = productVersion;
        this.androidApi = androidApi;
    }

    /**
     * Classifies the fixed synthetic trace and stores it in a fresh outbox instance.
     * @param now current wall clock time in milliseconds
     * @return the actual outbox result, {@code REJECTED} or {@code ERROR}
     */
    public String captureSynthetic(final long now) {
        try {
            final Optional<SanitizedAppFault> fault =
                    SanitizedAppFault.fromAcra(SYNTHETIC_TRACE, productVersion, androidApi);
            if (!fault.isPresent()) {
                return REJECTED;
            }
            final AppFaultOutbox.Result result =
                    new AppFaultOutbox(directory).enqueue(fault.get(), now);
            switch (result) {
                case ADDED:
                    return ADDED;
                case DUPLICATE:
                    return DUPLICATE;
                case RATE_LIMITED:
                    return RATE_LIMITED;
                case FULL:
                    return FULL;
                default:
                    return ERROR;
            }
        } catch (final IOException | RuntimeException ignored) {
            // Fixed status only; never expose, log, reset or repair.
            return ERROR;
        }
    }

    /**
     * Renders the preserved local ledger using only the safe public fields.
     * @param now current wall clock time in milliseconds
     * @return Korean status text with record count, safe JSON and a privacy boolean
     */
    public String snapshot(final long now) {
        try {
            final List<SanitizedAppFault> pending = new AppFaultOutbox(directory).pending(now);
            final boolean clean = persistedOutboxIsClean();
            final StringBuilder text = new StringBuilder();
            text.append("상태: 정상\n");
            text.append("기록 수: ").append(pending.size()).append('\n');
            for (int i = 0; i < pending.size(); i++) {
                text.append("기록 ").append(i + 1).append(": ")
                        .append(pending.get(i).toJson()).append('\n');
            }
            text.append("민감 표식 없음: ").append(clean);
            return text.toString();
        } catch (final IOException | RuntimeException ignored) {
            return errorSnapshot();
        }
    }

    /**
     * Fixed snapshot text shown when the ledger cannot be read.
     * @return Korean error snapshot without any exception detail
     */
    public static String errorSnapshot() {
        return "상태: " + ERROR + "\n" + meaning(ERROR);
    }

    /**
     * Korean meaning of a status returned by {@link #captureSynthetic(long)}.
     * @param status status value
     * @return short Korean explanation
     */
    public static String meaning(final String status) {
        if (ADDED.equals(status)) {
            return "새 기록이 이 기기 안에 저장되었습니다.";
        } else if (DUPLICATE.equals(status)) {
            return "같은 기록이 이미 있어 새로 저장하지 않았습니다. (중복 방지가 정상 동작)";
        } else if (RATE_LIMITED.equals(status)) {
            return "하루 저장 한도에 걸려 저장하지 않았습니다.";
        } else if (FULL.equals(status)) {
            return "로컬 기록 칸이 가득 차서 저장하지 않았습니다.";
        } else if (REJECTED.equals(status)) {
            return "합성 오류가 안전 분류를 통과하지 못해 저장하지 않았습니다.";
        }
        return "로컬 기록을 읽거나 쓸 수 없습니다. 기록은 지우거나 고치지 않았습니다.";
    }

    /** Bounded scan of only this probe's own persisted ledger for the fixed fake markers. */
    private boolean persistedOutboxIsClean() throws IOException {
        final File file = new File(directory, OUTBOX_FILE);
        if (!file.exists()) {
            return true;
        }
        final byte[] buffer = new byte[MAX_SCAN_BYTES];
        int length = 0;
        try (InputStream input = new FileInputStream(file)) {
            while (length < buffer.length) {
                final int read = input.read(buffer, length, buffer.length - length);
                if (read < 0) {
                    break;
                }
                length += read;
            }
            if (length == buffer.length && input.read() != -1) {
                // Larger than the scan bound: cannot confirm, so report not clean.
                return false;
            }
        }
        final String latin = new String(buffer, 0, length, StandardCharsets.ISO_8859_1);
        final String utf8 = new String(buffer, 0, length, StandardCharsets.UTF_8);
        final String utf16 = new String(buffer, 0, length, StandardCharsets.UTF_16BE);
        for (final String marker : SENSITIVE_MARKERS) {
            if (latin.contains(marker) || utf8.contains(marker) || utf16.contains(marker)) {
                return false;
            }
        }
        return true;
    }
}
