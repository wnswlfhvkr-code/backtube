package org.schabi.newpipe.offline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import org.junit.Test;

/** The old Wi-Fi-only preference and metered/validated flags no longer block saves. */
public class OfflineNetworkPolicyTest {
    @Test public void connectedWifiIsAllowedWithoutValidationOrUnmeteredFlag() {
        final Fixture fixture = new Fixture();
        when(fixture.info.isConnected()).thenReturn(true);
        when(fixture.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(true);
        assertTrue(OfflineDownloads.networkAllowed(fixture.context));
    }

    @Test public void connectedMobileIsEligibleForPerMissionConsent() {
        final Fixture fixture = new Fixture();
        when(fixture.info.isConnected()).thenReturn(true);
        when(fixture.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(true);
        assertTrue(OfflineDownloads.networkAllowed(fixture.context));
    }

    @Test public void disconnectedAndUnknownTransportsDoNotStart() {
        final Fixture fixture = new Fixture();
        assertFalse(OfflineDownloads.networkAllowed(fixture.context));
        when(fixture.info.isConnected()).thenReturn(true);
        assertFalse(OfflineDownloads.networkAllowed(fixture.context));
    }

    private static final class Fixture {
        final Context context = mock(Context.class);
        final ConnectivityManager manager = mock(ConnectivityManager.class);
        final Network network = mock(Network.class);
        final NetworkInfo info = mock(NetworkInfo.class);
        final NetworkCapabilities capabilities = mock(NetworkCapabilities.class);

        Fixture() {
            when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(manager);
            when(manager.getActiveNetwork()).thenReturn(network);
            when(manager.getActiveNetworkInfo()).thenReturn(info);
            when(manager.getNetworkCapabilities(network)).thenReturn(capabilities);
        }
    }
}
