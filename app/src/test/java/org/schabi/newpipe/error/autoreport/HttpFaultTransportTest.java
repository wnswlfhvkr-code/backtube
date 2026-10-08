package org.schabi.newpipe.error.autoreport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.OkHttpClient;

public class HttpFaultTransportTest {
    private static final int EXPECTED_CALL_TIMEOUT_MILLIS = 20_000;
    private static final String VALID_ENDPOINT = "https://relay.example/v1/fault";

    @Test
    public void newClientIsIndependentAndHardened() {
        final OkHttpClient client = HttpFaultTransport.newClient();

        assertSame(CookieJar.NO_COOKIES, client.cookieJar());
        assertSame(Authenticator.NONE, client.authenticator());
        assertSame(Authenticator.NONE, client.proxyAuthenticator());
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
        assertFalse(client.retryOnConnectionFailure());
        assertTrue(client.interceptors().isEmpty());
        assertTrue(client.networkInterceptors().isEmpty());
        assertEquals(EXPECTED_CALL_TIMEOUT_MILLIS, client.callTimeoutMillis());
    }

    @Test
    public void newClientReturnsSeparateInstances() {
        final OkHttpClient first = HttpFaultTransport.newClient();
        final OkHttpClient second = HttpFaultTransport.newClient();
        assertFalse(first == second);
    }

    @Test
    public void constructorRejectsNullEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> new HttpFaultTransport(null));
    }

    @Test
    public void constructorRejectsEmptyEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> new HttpFaultTransport(""));
    }

    @Test
    public void constructorRejectsPlainHttpEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpFaultTransport("http://relay.example/v1/fault"));
    }

    @Test
    public void constructorRejectsUserInfoEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpFaultTransport("https://u:p@relay.example/v1/fault"));
    }

    @Test
    public void constructorRejectsQueryEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpFaultTransport("https://relay.example/v1/fault?query"));
    }

    @Test
    public void validEndpointConstructsAndClosesWithoutNetwork() throws Exception {
        final HttpFaultTransport transport = new HttpFaultTransport(VALID_ENDPOINT);
        transport.close();
    }
}
