package org.schabi.newpipe.error.autoreport;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure protocol helpers for the sanitized app fault relay.
 *
 * <p>Validates the build-time relay endpoint and strictly parses relay responses. Only fixed
 * enum values and bounded numbers are extracted; raw response bodies and headers are never
 * retained, returned or logged.</p>
 */
public final class AppFaultHttpProtocol {
    /** Required relay path. */
    static final String FAULT_PATH = "/v1/fault";
    /** Maximum accepted response body size in UTF-8 bytes. */
    static final int MAX_BODY_BYTES = 2048;
    /** Minimum honoured server retry delay. */
    static final long MIN_RETRY_AFTER_MILLIS = 15L * 60_000L;
    /** Maximum honoured server retry delay. */
    static final long MAX_RETRY_AFTER_MILLIS = 24L * 60L * 60_000L;

    private static final int MAX_PORT = 65_535;
    private static final int MAX_SECONDS_DIGITS = 9;
    private static final int HTTP_OK = 200;
    private static final int HTTP_CREATED = 201;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final String WS = "[ \\t\\r\\n]*";
    private static final String STATUS = "\"status\"" + WS + ":" + WS
            + "\"(created|duplicate)\"";
    private static final String NUMBER = "\"issue_number\"" + WS + ":" + WS
            + "([1-9][0-9]{0,9})";
    private static final Pattern STATUS_FIRST = Pattern.compile(
            WS + "\\{" + WS + STATUS + WS + "," + WS + NUMBER + WS + "\\}" + WS);
    private static final Pattern NUMBER_FIRST = Pattern.compile(
            WS + "\\{" + WS + NUMBER + WS + "," + WS + STATUS + WS + "\\}" + WS);
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private AppFaultHttpProtocol() {
    }

    /**
     * Checks whether a build-time relay endpoint is set and strictly valid: an absolute
     * {@code https} URI with a host, optional valid port, no user info, query or fragment,
     * and exactly the {@code /v1/fault} path.
     * @param endpoint the configured endpoint, possibly {@code null} or empty
     * @return {@code true} only if the endpoint is usable for fault delivery
     */
    public static boolean isEndpointConfigured(final String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return false;
        }
        for (int i = 0; i < endpoint.length(); i++) {
            final char c = endpoint.charAt(i);
            if (c <= ' ' || c >= 0x7f) {
                return false;
            }
        }
        final URI uri;
        try {
            uri = new URI(endpoint);
        } catch (final URISyntaxException e) {
            return false;
        }
        if (uri.isOpaque() || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        final String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            return false;
        }
        final int port = uri.getPort();
        if (port != -1 && (port < 1 || port > MAX_PORT)) {
            return false;
        }
        return FAULT_PATH.equals(uri.getRawPath());
    }

    /**
     * Parses a relay response into numeric fields only. A response is confirmed only for
     * status 200 or 201 with a body of exactly the two keys {@code status}
     * ({@code created} or {@code duplicate}) and a positive integer {@code issue_number}.
     * @param code the HTTP status code
     * @param body the response body, possibly {@code null}; never retained
     * @param retryAfter the {@code Retry-After} header value, possibly {@code null}
     * @param now current wall clock time in milliseconds
     * @return the parsed response, never {@code null}
     */
    public static AppFaultDelivery.Response parseResponse(final int code, final String body,
                                                          final String retryAfter,
                                                          final long now) {
        if (code == HTTP_OK || code == HTTP_CREATED) {
            final long issueNumber = parseIssueNumber(body);
            return new AppFaultDelivery.Response(code, issueNumber > 0, Math.max(0L, issueNumber),
                    0L);
        }
        if (code == HTTP_TOO_MANY_REQUESTS) {
            return new AppFaultDelivery.Response(code, false, 0L,
                    parseRetryAfter(retryAfter, now));
        }
        return new AppFaultDelivery.Response(code, false, 0L, 0L);
    }

    /**
     * Parses a {@code Retry-After} header as delta seconds or an RFC 1123 GMT date.
     * @param header the header value, possibly {@code null}
     * @param now current wall clock time in milliseconds
     * @return a delay clamped to between 15 minutes and 24 hours, or 0 when absent or invalid
     */
    static long parseRetryAfter(final String header, final long now) {
        if (header == null) {
            return 0L;
        }
        final String value = header.trim();
        if (value.isEmpty() || value.length() > 64) {
            return 0L;
        }
        if (DIGITS.matcher(value).matches()) {
            if (value.length() > MAX_SECONDS_DIGITS) {
                return MAX_RETRY_AFTER_MILLIS;
            }
            return clamp(Long.parseLong(value) * 1000L);
        }
        if (!value.endsWith(" GMT")) {
            return 0L;
        }
        try {
            final long at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            return clamp(at - now);
        } catch (final DateTimeException | ArithmeticException e) {
            return 0L;
        }
    }

    private static long clamp(final long millis) {
        return Math.min(MAX_RETRY_AFTER_MILLIS, Math.max(MIN_RETRY_AFTER_MILLIS, millis));
    }

    private static long parseIssueNumber(final String body) {
        if (body == null || body.length() > MAX_BODY_BYTES
                || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return -1L;
        }
        String digits = null;
        Matcher matcher = STATUS_FIRST.matcher(body);
        if (matcher.matches()) {
            digits = matcher.group(2);
        } else {
            matcher = NUMBER_FIRST.matcher(body);
            if (matcher.matches()) {
                digits = matcher.group(1);
            }
        }
        if (digits == null) {
            return -1L;
        }
        final long number = Long.parseLong(digits);
        return number > 0 && number <= Integer.MAX_VALUE ? number : -1L;
    }
}
