package org.schabi.newpipe.offline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.mockito.MockedStatic;

/** A connected Wi-Fi transport can still be disallowed by the save policy. */
public class OfflineNetworkPolicyTest {
    @Test public void defaultWifiOnlyRequiresValidatedUnmeteredWifi() {
        final Context context = mock(Context.class);
        final ConnectivityManager manager = mock(ConnectivityManager.class);
        final Network network = mock(Network.class);
        final NetworkCapabilities capabilities = mock(NetworkCapabilities.class);
        final SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(manager);
        when(manager.getActiveNetwork()).thenReturn(network);
        when(manager.getNetworkCapabilities(network)).thenReturn(capabilities);
        when(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true);
        when(preferences.getBoolean(OfflineDownloads.WIFI_ONLY, true)).thenReturn(true);
        try (MockedStatic<PreferenceManager> settings = mockStatic(PreferenceManager.class)) {
            settings.when(() -> PreferenceManager.getDefaultSharedPreferences(context))
                    .thenReturn(preferences);

            assertFalse(OfflineDownloads.networkAllowed(context));
            when(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    .thenReturn(true);
            assertFalse(OfflineDownloads.networkAllowed(context));
            when(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                    .thenReturn(true);
            assertTrue(OfflineDownloads.networkAllowed(context));
        }
    }

    @Test public void allowingOtherNetworksStillRequiresValidatedInternet() {
        final Context context = mock(Context.class);
        final ConnectivityManager manager = mock(ConnectivityManager.class);
        final Network network = mock(Network.class);
        final NetworkCapabilities capabilities = mock(NetworkCapabilities.class);
        final SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(manager);
        when(manager.getActiveNetwork()).thenReturn(network);
        when(manager.getNetworkCapabilities(network)).thenReturn(capabilities);
        when(preferences.getBoolean(OfflineDownloads.WIFI_ONLY, true)).thenReturn(false);
        try (MockedStatic<PreferenceManager> settings = mockStatic(PreferenceManager.class)) {
            settings.when(() -> PreferenceManager.getDefaultSharedPreferences(context))
                    .thenReturn(preferences);
            assertFalse(OfflineDownloads.networkAllowed(context));
            when(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    .thenReturn(true);
            assertTrue(OfflineDownloads.networkAllowed(context));
        }
    }
}
