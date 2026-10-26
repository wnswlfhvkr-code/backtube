package org.schabi.newpipe.download;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import org.junit.Test;

public class DownloadNetworkPolicyTest {
    @Test public void connectedMeteredUnvalidatedWifiCanStartWithoutApproval() {
        final Fixture fixture = new Fixture();
        when(fixture.info.isConnected()).thenReturn(true);
        when(fixture.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(true);
        assertEquals(DownloadNetworkPolicy.Type.WIFI,
                DownloadNetworkPolicy.current(fixture.context));
    }

    @Test public void cellularNeedsPerMissionApproval() {
        final Fixture fixture = new Fixture();
        when(fixture.info.isConnected()).thenReturn(true);
        when(fixture.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(true);
        assertEquals(DownloadNetworkPolicy.Type.MOBILE,
                DownloadNetworkPolicy.current(fixture.context));
    }

    @Test public void disconnectedOrUnknownTransportIsNotWifiOrMobile() {
        final Fixture fixture = new Fixture();
        assertEquals(DownloadNetworkPolicy.Type.NONE,
                DownloadNetworkPolicy.current(fixture.context));
        when(fixture.info.isConnected()).thenReturn(true);
        assertEquals(DownloadNetworkPolicy.Type.OTHER,
                DownloadNetworkPolicy.current(fixture.context));
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
