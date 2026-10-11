package org.schabi.newpipe.error.autoreport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

/**
 * Protocol tests for the strict relay endpoint check and response parser. Responses are
 * verified only through the persisted outbox outcome; no real HTTP calls are made.
 */
public class AppFaultHttpProtocolTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long LEASE = 15 * MINUTE;
    private static final long DAY = 86_400_000L;
    private static final String CREATED = "{\"status\":\"created\",\"issue_number\":42}";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static SanitizedAppFault fault() {
        return new SanitizedAppFault(SanitizedAppFault.Fault.NULL_POINTER,
                SanitizedAppFault.Component.PLAYER, 1000, 35);
    }

    private AppFaultOutbox deliver(final int code, final String body, final String retryAfter)
            throws IOException {
        // Parsed outside the transport so a parser exception fails the test instead of
        // being swallowed by the delivery retry path.
        final AppFaultDelivery.Response response =
                AppFaultHttpProtocol.parseResponse(code, body, retryAfter, NOW);
        assertNotNull(response);
        final AppFaultOutbox outbox = new AppFaultOutbox(folder.newFolder());
        assertEquals(AppFaultOutbox.Result.ADDED, outbox.enqueue(fault(), NOW));
        assertTrue(new AppFaultDelivery(outbox, sent -> response).deliverOne(NOW));
        return outbox;
    }

    private static void assertAcked(final AppFaultOutbox outbox) throws IOException {
        assertTrue(outbox.pending(NOW).isEmpty());
        assertEquals(-1L, outbox.nextEligibleAt(NOW));
    }

    private static void assertNotAcked(final AppFaultOutbox outbox) throws IOException {
        assertEquals(Collections.singletonList(fault()), outbox.pending(NOW));
        assertTrue(outbox.nextEligibleAt(NOW) > NOW);
    }

    private static String padded(final String json, final int size) {
        final StringBuilder builder = new StringBuilder(json);
        while (builder.length() < size) {
            builder.append(' ');
        }
        return builder.toString();
    }

    @Test
    public void endpointUnsetOrEmptyIsNotConfigured() {
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured(null));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured(""));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured("   "));
    }

    @Test
    public void endpointRejectsNonHttpsAndRelative() {
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured(
                "http://relay.example.workers.dev/v1/fault"));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured("ftp://relay.example/v1/fault"));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured("/v1/fault"));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured("https:///v1/fault"));
    }

    @Test
    public void endpointRejectsUserInfoQueryFragmentAndWrongPath() {
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured(
                "https://user:secret@relay.example.workers.dev/v1/fault"));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured(
                "https://relay.example.workers.dev/v1/fault?token=x"));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured(
                "https://relay.example.workers.dev/v1/fault#frag"));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured(
                "https://relay.example.workers.dev/v1/other"));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured(
                "https://relay.example.workers.dev/"));
    }

    @Test
    public void endpointRejectsNonsense() {
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured("not a url"));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured("https://"));
        assertFalse(AppFaultHttpProtocol.isEndpointConfigured("https://exa mple/v1/fault"));
    }

    @Test
    public void endpointAcceptsAbsoluteHttpsRelayPath() {
        assertTrue(AppFaultHttpProtocol.isEndpointConfigured(
                "https://relay.example.workers.dev/v1/fault"));
    }

    @Test
    public void createdWith201IsAcknowledged() throws IOException {
        assertAcked(deliver(201, CREATED, null));
    }

    @Test
    public void duplicateWith200InEitherFieldOrderIsAcknowledged() throws IOException {
        assertAcked(deliver(200, "{\"status\":\"duplicate\",\"issue_number\":7}", null));
        assertAcked(deliver(200, "{\"issue_number\":7,\"status\":\"duplicate\"}", null));
    }

    @Test
    public void extraOrUnknownFieldsAreNeverAcknowledged() throws IOException {
        assertNotAcked(deliver(201,
                "{\"status\":\"created\",\"issue_number\":42,\"url\":\"x\"}", null));
        assertNotAcked(deliver(201, "{\"status\":\"ok\",\"issue_number\":42}", null));
        assertNotAcked(deliver(201, "{\"status\":\"created\"}", null));
    }

    @Test
    public void nonPositiveOrNonIntegerIssueNumberIsNeverAcknowledged() throws IOException {
        assertNotAcked(deliver(201, "{\"status\":\"created\",\"issue_number\":0}", null));
        assertNotAcked(deliver(201, "{\"status\":\"created\",\"issue_number\":-3}", null));
        assertNotAcked(deliver(201, "{\"status\":\"created\",\"issue_number\":1.5}", null));
        assertNotAcked(deliver(201,
                "{\"status\":\"created\",\"issue_number\":\"42\"}", null));
    }

    @Test
    public void malformedOrOversizedBodyIsNeverAcknowledged() throws IOException {
        assertNotAcked(deliver(201, null, null));
        assertNotAcked(deliver(201, "{\"status\":\"created\",", null));
        assertNotAcked(deliver(201, padded(CREATED, 3000), null));
    }

    @Test
    public void retryAfterSecondsPersistsBoundedRetryDeadline() throws IOException {
        assertEquals(NOW + 60 * MINUTE, deliver(429, null, "3600").nextEligibleAt(NOW));
        assertEquals(NOW + LEASE, deliver(429, null, "10").nextEligibleAt(NOW));
        assertEquals(NOW + DAY, deliver(429, null, "9999999").nextEligibleAt(NOW));
    }

    @Test
    public void retryAfterHttpDateAndMalformedFallBackSafely() throws IOException {
        final String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(NOW + 120 * MINUTE).atZone(ZoneOffset.UTC));
        assertEquals(NOW + 120 * MINUTE, deliver(429, null, date).nextEligibleAt(NOW));
        final AppFaultOutbox malformed = deliver(429, "{}", "soon-ish");
        assertNotAcked(malformed);
        assertEquals(NOW + LEASE, malformed.nextEligibleAt(NOW));
    }
}
