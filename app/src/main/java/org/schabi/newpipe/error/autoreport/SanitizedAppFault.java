package org.schabi.newpipe.error.autoreport;

import java.util.Objects;
import java.util.Optional;

/** An allowlisted public payload. Raw trace text is never retained by this object. */
public final class SanitizedAppFault {
    enum Fault { NULL_POINTER, ILLEGAL_STATE, INDEX_BOUNDS, CONCURRENT_MODIFICATION, ASSERTION }
    enum Component { PLAYER, UI, APP }

    private final Fault fault;
    private final Component component;
    private final int version;
    private final int api;

    SanitizedAppFault(final Fault fault, final Component component,
                      final int version, final int api) {
        this.fault = Objects.requireNonNull(fault);
        this.component = Objects.requireNonNull(component);
        if (version <= 0 || api < 23 || api > 100) {
            throw new IllegalArgumentException("Invalid public build metadata");
        }
        this.version = version;
        this.api = api;
    }

    /**
     * Unknown, malformed, provider, transport and data exceptions fail closed.
     * @param trace ACRA trace, inspected transiently and never retained
     * @param version numeric application version code
     * @param api Android API level
     * @return only fixed public metadata, or empty when classification is uncertain
     */
    public static Optional<SanitizedAppFault> fromAcra(final String trace,
                                                      final int version, final int api) {
        if (trace == null || trace.isEmpty() || trace.length() > 65536
                || version <= 0 || api < 23 || api > 100) {
            return Optional.empty();
        }
        final String[] lines = trace.split("\\r?\\n", -1);
        if (lines.length > 256) {
            return Optional.empty();
        }
        Fault selected = null;
        Component origin = null;
        boolean needsFrame = false;
        boolean faultHeader = false;
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].trim();
            if (line.isEmpty() && i == lines.length - 1) {
                continue;
            }
            if (line.contains("org.schabi.newpipe.extractor.")
                    || line.startsWith("Suppressed:")) {
                return Optional.empty();
            }
            if (i == 0 || line.startsWith("Caused by: ")) {
                if (needsFrame) {
                    return Optional.empty();
                }
                final String header = i == 0 ? line : line.substring(11);
                final int colon = header.indexOf(':');
                final String type = colon < 0 ? header : header.substring(0, colon);
                final Fault parsed = faultType(type);
                if (parsed == null && !isWrapper(type)) {
                    return Optional.empty();
                }
                faultHeader = parsed != null;
                if (faultHeader) {
                    selected = parsed;
                }
                needsFrame = true;
            } else if (line.matches("at [a-zA-Z0-9_.$]+\\([^\\r\\n]*\\)")) {
                if (needsFrame && faultHeader) {
                    origin = componentOf(line.substring(3, line.indexOf('(')));
                    if (origin == null) {
                        return Optional.empty();
                    }
                }
                needsFrame = false;
            } else if (!line.matches("\\.\\.\\. [0-9]+ more") || needsFrame) {
                return Optional.empty();
            }
        }
        if (needsFrame || !faultHeader || selected == null || origin == null) {
            return Optional.empty();
        }
        return Optional.of(new SanitizedAppFault(selected, origin, version, api));
    }

    private static Fault faultType(final String type) {
        switch (type) {
            case "java.lang.NullPointerException": return Fault.NULL_POINTER;
            case "java.lang.IllegalStateException": return Fault.ILLEGAL_STATE;
            case "java.lang.IndexOutOfBoundsException":
            case "java.lang.ArrayIndexOutOfBoundsException": return Fault.INDEX_BOUNDS;
            case "java.util.ConcurrentModificationException": return Fault.CONCURRENT_MODIFICATION;
            case "java.lang.AssertionError": return Fault.ASSERTION;
            default: return null;
        }
    }

    private static boolean isWrapper(final String type) {
        return "java.lang.RuntimeException".equals(type)
                || "java.util.concurrent.ExecutionException".equals(type)
                || "io.reactivex.rxjava3.exceptions.UndeliverableException".equals(type);
    }

    private static Component componentOf(final String frame) {
        if (frame.startsWith("org.schabi.newpipe.player.")) {
            return Component.PLAYER;
        }
        if (frame.startsWith("org.schabi.newpipe.fragments.")
                || frame.startsWith("org.schabi.newpipe.views.")
                || frame.startsWith("org.schabi.newpipe.MainActivity.")) {
            return Component.UI;
        }
        return frame.startsWith("org.schabi.newpipe.App.") ? Component.APP : null;
    }

    public String toJson() {
        return "{\"schema\":1,\"fault\":\"" + fault + "\",\"component\":\"" + component
                + "\",\"app_version_code\":" + version + ",\"android_api\":" + api + "}";
    }

    public String issueTitle() {
        return "[App fault] " + fault + " / " + component;
    }

    public String issueBody() {
        return "Automatic sanitized app fault (schema 1).\n\n```json\n" + toJson() + "\n```";
    }

    Fault fault() {
        return fault;
    }
    Component component() {
        return component;
    }
    int version() {
        return version;
    }
    int api() {
        return api;
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof SanitizedAppFault)) {
            return false;
        }
        final SanitizedAppFault that = (SanitizedAppFault) other;
        return fault == that.fault && component == that.component
                && version == that.version && api == that.api;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fault, component, version, api);
    }
}
