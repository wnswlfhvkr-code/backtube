package org.schabi.newpipe.error.autoreport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Sends one sanitized app fault to the configured relay over a dedicated, hardened HTTP client.
 *
 * <p>The client is never shared with the app downloader: it has no cookies, no authenticators,
 * no redirects, no automatic retries and no interceptors. Only the allowlisted JSON payload is
 * sent. Response bodies are read with a hard size bound, parsed strictly into numeric fields
 * and never retained or logged.</p>
 */
public final class HttpFaultTransport implements AppFaultDelivery.Transport, AutoCloseable {
    /** Overall timeout for one relay call. */
    static final long CALL_TIMEOUT_SECONDS = 20L;

    private static final MediaType JSON = MediaType.get("application/json");
    private static final int HTTP_OK = 200;
    private static final int HTTP_CREATED = 201;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int READ_CHUNK = 512;

    private final String endpoint;
    private final OkHttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Call activeCall;

    /**
     * Creates a transport for a strictly validated relay endpoint.
     * @param endpoint the build-time relay endpoint
     * @throws IllegalArgumentException if the endpoint is missing or not strictly valid
     */
    public HttpFaultTransport(final String endpoint) {
        if (!AppFaultHttpProtocol.isEndpointConfigured(endpoint)) {
            throw new IllegalArgumentException("Fault relay endpoint is not configured");
        }
        this.endpoint = endpoint;
        this.client = newClient();
    }

    /**
     * Builds a fresh, independent and hardened HTTP client for fault delivery.
     * @return a new client instance never shared with other components
     */
    static OkHttpClient newClient() {
        return new OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Sends the allowlisted payload of one fault.
     * @param fault the sanitized fault to send
     * @return the strictly parsed relay response
     * @throws IOException on timeout, connection failure or if this transport is closed
     */
    @Override
    public AppFaultDelivery.Response send(final SanitizedAppFault fault) throws IOException {
        if (fault == null) {
            throw new IOException("Missing fault");
        }
        if (closed.get()) {
            throw new IOException("Transport closed");
        }
        final byte[] payload = fault.toJson().getBytes(StandardCharsets.UTF_8);
        final Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(payload, JSON))
                .build();
        final Call call = client.newCall(request);
        activeCall = call;
        try {
            if (closed.get()) {
                call.cancel();
                throw new IOException("Transport closed");
            }
            try (Response response = call.execute()) {
                return toResponse(response);
            }
        } finally {
            activeCall = null;
        }
    }

    private static AppFaultDelivery.Response toResponse(final Response response)
            throws IOException {
        final int code = response.code();
        final long now = System.currentTimeMillis();
        if (code == HTTP_OK || code == HTTP_CREATED) {
            return AppFaultHttpProtocol.parseResponse(code, readBoundedBody(response.body()),
                    null, now);
        }
        if (code == HTTP_TOO_MANY_REQUESTS) {
            return AppFaultHttpProtocol.parseResponse(code, null,
                    response.header("Retry-After"), now);
        }
        return AppFaultHttpProtocol.parseResponse(code, null, null, now);
    }

    /**
     * Reads at most {@code MAX_BODY_BYTES + 1} bytes and decodes them strictly as UTF-8.
     * @param body the response body, possibly {@code null}
     * @return the decoded body, or {@code null} if absent, oversized or malformed
     * @throws IOException on read failure
     */
    private static String readBoundedBody(final ResponseBody body) throws IOException {
        if (body == null) {
            return null;
        }
        final int limit = AppFaultHttpProtocol.MAX_BODY_BYTES + 1;
        final ByteArrayOutputStream out = new ByteArrayOutputStream(READ_CHUNK);
        try (InputStream in = body.byteStream()) {
            final byte[] buffer = new byte[READ_CHUNK];
            int total = 0;
            while (total < limit) {
                final int read = in.read(buffer, 0, Math.min(buffer.length, limit - total));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                total += read;
            }
            if (total > AppFaultHttpProtocol.MAX_BODY_BYTES) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString();
        } catch (final CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Cancels any in-flight call and releases the client's connections and threads.
     * Calling this more than once has no further effect.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        final Call call = activeCall;
        if (call != null) {
            call.cancel();
        }
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
