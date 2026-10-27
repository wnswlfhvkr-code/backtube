package org.schabi.newpipe.player.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSpec;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;
import org.mockito.MockedConstruction;

/** Connection doubles exercise the actual data source setup method without network requests. */
public class YoutubeHttpDataSourceSetupTest {
    @Test
    public void connectFailureDisconnectsLocalConnectionAndPreservesOriginalError()
            throws Exception {
        final HttpURLConnection connection = mock(HttpURLConnection.class);
        final IOException original = new IOException("generated connect failure");
        doThrow(original).when(connection).connect();
        final URL originalUrl = originalUrl();

        try (MockedConstruction<URL> ignored = connectionForConstructedUrl(connection)) {
            assertSame(original, invokeFailure(originalUrl));
        }

        verify(connection).disconnect();
    }

    @Test
    public void writeFailureClosesPostStreamAndDisconnectsLocalConnection() throws Exception {
        final HttpURLConnection connection = mock(HttpURLConnection.class);
        final OutputStream output = mock(OutputStream.class);
        final IOException original = new IOException("generated POST write failure");
        when(connection.getOutputStream()).thenReturn(output);
        doThrow(original).when(output).write(any(byte[].class));
        final URL originalUrl = originalUrl();

        try (MockedConstruction<URL> ignored = connectionForConstructedUrl(connection)) {
            assertSame(original, invokeFailure(originalUrl));
        }

        verify(output).close();
        verify(connection).disconnect();
    }

    @Test
    public void outputStreamOpenFailureDisconnectsLocalConnection() throws Exception {
        final HttpURLConnection connection = mock(HttpURLConnection.class);
        final IOException original = new IOException("generated output stream open failure");
        when(connection.getOutputStream()).thenThrow(original);
        final URL originalUrl = originalUrl();

        try (MockedConstruction<URL> ignored = connectionForConstructedUrl(connection)) {
            assertSame(original, invokeFailure(originalUrl));
        }

        verify(connection).disconnect();
    }

    @Test
    public void outputStreamCloseFailureDisconnectsLocalConnection() throws Exception {
        final HttpURLConnection connection = mock(HttpURLConnection.class);
        final OutputStream output = mock(OutputStream.class);
        final IOException original = new IOException("generated output stream close failure");
        when(connection.getOutputStream()).thenReturn(output);
        doThrow(original).when(output).close();
        final URL originalUrl = originalUrl();

        try (MockedConstruction<URL> ignored = connectionForConstructedUrl(connection)) {
            assertSame(original, invokeFailure(originalUrl));
        }

        verify(output, times(1)).close();
        verify(connection).disconnect();
    }

    @Test
    public void requestMethodFailureDisconnectsConnectionBeforeItIsReturned() throws Exception {
        final HttpURLConnection connection = mock(HttpURLConnection.class);
        final ProtocolException original =
                new ProtocolException("generated request method failure");
        doThrow(original).when(connection).setRequestMethod("POST");
        final URL originalUrl = originalUrl();

        try (MockedConstruction<URL> ignored = connectionForConstructedUrl(connection)) {
            assertSame(original, invokeFailure(originalUrl));
        }

        verify(connection).disconnect();
    }

    @Test
    public void streamingModeFailureDisconnectsConnectionBeforeItIsReturned() throws Exception {
        final HttpURLConnection connection = mock(HttpURLConnection.class);
        final IllegalStateException original = new IllegalStateException("generated mode failure");
        doThrow(original).when(connection).setFixedLengthStreamingMode(2);
        final URL originalUrl = originalUrl();

        try (MockedConstruction<URL> ignored = connectionForConstructedUrl(connection)) {
            assertSame(original, invokeFailure(originalUrl));
        }

        verify(connection).disconnect();
    }

    @Test
    public void redirectResponseFailureDisconnectsLocalConnection() throws Exception {
        final HttpURLConnection connection = connectedResponseDouble();
        final IOException original = new IOException("generated response failure");
        when(connection.getResponseCode()).thenThrow(original);

        assertSame(original, invokeRedirectFailure(connection));

        verify(connection).disconnect();
    }

    @Test
    public void redirectHeaderFailureDisconnectsLocalConnection() throws Exception {
        final HttpURLConnection connection = connectedResponseDouble();
        final IllegalStateException original =
                new IllegalStateException("generated header failure");
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(connection.getHeaderField("Location")).thenThrow(original);

        assertSame(original, invokeRedirectFailure(connection));

        verify(connection).disconnect();
    }

    @Test
    public void redirectCleanupFailureDoesNotReplaceOriginalResponseFailure()
            throws Exception {
        final HttpURLConnection connection = connectedResponseDouble();
        final IOException original = new IOException("generated response failure");
        final IllegalStateException cleanup =
                new IllegalStateException("generated disconnect failure");
        when(connection.getResponseCode()).thenThrow(original);
        doThrow(cleanup).when(connection).disconnect();

        assertSame(original, invokeRedirectFailure(connection));
        assertEquals(1, original.getSuppressed().length);
        assertSame(cleanup, original.getSuppressed()[0]);
    }

    @Test
    public void cleanupFailureIsSuppressedWithoutReplacingConnectFailure() throws Exception {
        final HttpURLConnection connection = mock(HttpURLConnection.class);
        final IOException original = new IOException("generated connect failure");
        final IllegalStateException cleanup =
                new IllegalStateException("generated cleanup failure");
        doThrow(original).when(connection).connect();
        doThrow(cleanup).when(connection).disconnect();
        final URL originalUrl = originalUrl();

        final Throwable observed;
        try (MockedConstruction<URL> ignored = connectionForConstructedUrl(connection)) {
            observed = invokeFailure(originalUrl);
        }

        assertSame(original, observed);
        assertEquals(1, observed.getSuppressed().length);
        assertSame(cleanup, observed.getSuppressed()[0]);
    }

    @Test
    public void successfulPostClosesStreamOnceAndLeavesConnectionForCaller() throws Exception {
        final HttpURLConnection connection = mock(HttpURLConnection.class);
        final OutputStream output = mock(OutputStream.class);
        when(connection.getOutputStream()).thenReturn(output);
        final URL originalUrl = originalUrl();

        try (MockedConstruction<URL> ignored = connectionForConstructedUrl(connection)) {
            assertSame(connection, invokeConnection(originalUrl));
        }

        verify(output, times(1)).close();
        verify(connection, never()).disconnect();
    }

    private static MockedConstruction<URL> connectionForConstructedUrl(
            final HttpURLConnection connection) {
        return mockConstruction(URL.class, (constructed, context) ->
                when(constructed.openConnection()).thenReturn(connection));
    }

    private static HttpURLConnection connectedResponseDouble() throws Exception {
        final HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getOutputStream()).thenReturn(mock(OutputStream.class));
        return connection;
    }

    private static Throwable invokeRedirectFailure(final HttpURLConnection connection)
            throws Exception {
        final Uri uri = mock(Uri.class);
        when(uri.toString()).thenReturn("https://example.invalid/generated");
        final DataSpec request = new DataSpec(uri);
        final YoutubeHttpDataSource source = new YoutubeHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .createDataSource();
        final Method method = YoutubeHttpDataSource.class.getDeclaredMethod("makeConnection",
                DataSpec.class);
        method.setAccessible(true);
        try (MockedConstruction<URL> ignored = mockConstruction(URL.class,
                (constructed, context) -> {
                    when(constructed.toString()).thenReturn("https://example.invalid/generated");
                    when(constructed.getPath()).thenReturn("/generated");
                    when(constructed.openConnection()).thenReturn(connection);
                })) {
            try {
                method.invoke(source, request);
            } catch (final InvocationTargetException error) {
                return error.getCause();
            }
        }
        fail("Redirect response inspection should fail");
        return null;
    }

    private static URL originalUrl() throws Exception {
        return new URL("https://example.invalid/generated");
    }

    private static Throwable invokeFailure(final URL originalUrl) throws Exception {
        try {
            invokeConnection(originalUrl);
        } catch (final InvocationTargetException error) {
            return error.getCause();
        }
        fail("Connection setup should fail");
        return null;
    }

    private static HttpURLConnection invokeConnection(final URL originalUrl) throws Exception {
        final YoutubeHttpDataSource source = new YoutubeHttpDataSource.Factory()
                .createDataSource();
        final Method method = YoutubeHttpDataSource.class.getDeclaredMethod("makeConnection",
                URL.class, int.class, byte[].class, long.class, long.class, boolean.class,
                boolean.class, Map.class);
        method.setAccessible(true);
        return (HttpURLConnection) method.invoke(source, originalUrl, DataSpec.HTTP_METHOD_GET,
                null, 0L, C.LENGTH_UNSET, false, true, Collections.emptyMap());
    }
}
