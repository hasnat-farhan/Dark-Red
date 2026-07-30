package com.antor.sosblue.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitors Wi-Fi / network connectivity changes and notifies registered
 * listeners so they can re-bind sockets, clear stale peer caches, and
 * re-acquire multicast locks.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li>Uses {@link ConnectivityManager.NetworkCallback} (API 21+) as the
 *       primary network-change detector — fires on capabilities loss,
 *       link-properties change, and network disconnect.</li>
 *   <li>Falls back to a {@link BroadcastReceiver} for
 *       {@link WifiManager#NETWORK_STATE_CHANGED_ACTION} on older devices.</li>
 *   <li>On any network transition, calls {@link #notifyNetworkChanged()}
 *       which triggers all registered {@link NetworkChangeListener} callbacks.</li>
 *   <li>Also exposes the current local IP address so peers can send ACKs
 *       directly rather than relying on subnet broadcasts.</li>
 * </ul>
 */
public class NetworkConnectivityManager {

    private static final String TAG = "NetConnectivity";

    private final Context appContext;
    private final ConnectivityManager connectivityManager;
    private final CopyOnWriteArrayList<NetworkChangeListener> listeners;
    private final AtomicReference<String> currentLocalIp;

    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver wifiStateReceiver;
    private BroadcastReceiver networkStateReceiver;
    private boolean registered;

    /** Current Wi-Fi network name (SSID), cached at last network-change event. */
    private volatile String currentSsid;
    /** Timestamp of the last network change (epoch millis). */
    private volatile long lastNetworkChangeMs;

    // ---------------------------------------------------------------
    //  Listener interface
    // ---------------------------------------------------------------

    /**
     * Callback invoked when the Wi-Fi network changes (new network, IP
     * address change, disconnection, or capabilities lost).
     */
    public interface NetworkChangeListener {
        /**
         * Fired on the main thread when a network transition is detected.
         *
         * @param newLocalIp    the current local IP address (may be null if disconnected)
         * @param prevSsid      the previous Wi-Fi SSID (null if unknown)
         * @param currentSsid   the current Wi-Fi SSID (null if not on Wi-Fi)
         */
        void onNetworkChanged(String newLocalIp, String prevSsid, String currentSsid);
    }

    // ---------------------------------------------------------------
    //  Construction
    // ---------------------------------------------------------------

    public NetworkConnectivityManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.connectivityManager = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listeners = new CopyOnWriteArrayList<>();
        this.currentLocalIp = new AtomicReference<>(resolveLocalIp());
    }

    // ---------------------------------------------------------------
    //  Registration
    // ---------------------------------------------------------------

    /**
     * Registers all network listeners. Safe to call multiple times.
     */
    public synchronized void register() {
        if (registered) return;

        // ── Primary: ConnectivityManager.NetworkCallback ──────────────
        if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    Log.d(TAG, "Network available: " + network);
                    handleNetworkChange();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    Log.d(TAG, "Network lost: " + network);
                    handleNetworkChange();
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                                                   @NonNull NetworkCapabilities caps) {
                    boolean hasWifi = caps.hasTransport(
                            NetworkCapabilities.TRANSPORT_WIFI);
                    boolean hasInternet = caps.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    Log.d(TAG, "Network caps changed: wifi=" + hasWifi
                            + ", internet=" + hasInternet);
                    handleNetworkChange();
                }

                @Override
                public void onLinkPropertiesChanged(@NonNull Network network,
                                                     @NonNull LinkProperties lp) {
                    // Link properties change when IP address or DNS changes
                    Log.d(TAG, "Link properties changed: " + lp);
                    handleNetworkChange();
                }
            };

            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build();
            try {
                connectivityManager.registerNetworkCallback(request, networkCallback);
                Log.i(TAG, "ConnectivityManager.NetworkCallback registered");
            } catch (SecurityException e) {
                Log.w(TAG, "Cannot register network callback (missing permission): "
                        + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "Cannot register network callback", e);
            }
        }

        // ── Fallback: WifiManager.NETWORK_STATE_CHANGED_ACTION ────────
        wifiStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                    Log.d(TAG, "Wi-Fi network state changed (broadcast)");
                    handleNetworkChange();
                }
            }
        };
        IntentFilter wifiFilter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(wifiStateReceiver, wifiFilter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(wifiStateReceiver, wifiFilter);
        }

        // ── Also listen for CONNECTIVITY_ACTION (API < 24) ────────────
        networkStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ConnectivityManager.CONNECTIVITY_ACTION != null
                        && ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                    Log.d(TAG, "Connectivity changed (broadcast)");
                    handleNetworkChange();
                }
            }
        };
        IntentFilter connFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // CONNECTIVITY_ACTION is deprecated on N+, but still works on older
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(networkStateReceiver, connFilter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(networkStateReceiver, connFilter);
            }
        }

        registered = true;
        Log.i(TAG, "NetworkConnectivityManager registered");
    }

    /**
     * Unregisters all network listeners and releases references.
     */
    public synchronized void unregister() {
        if (!registered) return;

        if (networkCallback != null && connectivityManager != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        if (wifiStateReceiver != null) {
            try {
                appContext.unregisterReceiver(wifiStateReceiver);
            } catch (IllegalArgumentException ignored) {}
            wifiStateReceiver = null;
        }
        if (networkStateReceiver != null) {
            try {
                appContext.unregisterReceiver(networkStateReceiver);
            } catch (IllegalArgumentException ignored) {}
            networkStateReceiver = null;
        }
        registered = false;
        Log.i(TAG, "NetworkConnectivityManager unregistered");
    }

    // ---------------------------------------------------------------
    //  Listener management
    // ---------------------------------------------------------------

    public void addListener(NetworkChangeListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(NetworkChangeListener listener) {
        listeners.remove(listener);
    }

    // ---------------------------------------------------------------
    //  Public accessors
    // ---------------------------------------------------------------

    /**
     * Returns the current local IPv4 address, or {@code null} if no
     * active network interface is available.
     */
    public String getCurrentLocalIp() {
        return currentLocalIp.get();
    }

    /** Returns the last known Wi-Fi SSID, or null. */
    public String getCurrentSsid() {
        return currentSsid;
    }

    /** Returns the timestamp (epoch millis) of the last network change. */
    public long getLastNetworkChangeMs() {
        return lastNetworkChangeMs;
    }

    /**
     * Returns the network name the user is currently connected to,
     * or "No network" / "Unknown network".
     */
    public String getNetworkName() {
        String ip = currentLocalIp.get();
        String ssid = currentSsid;
        if (ip != null) {
            return (ssid != null) ? ssid + " (" + ip + ")" : ip;
        }
        return "No network";
    }

    // ---------------------------------------------------------------
    //  Internal
    // ---------------------------------------------------------------

    private void handleNetworkChange() {
        String prevIp = currentLocalIp.getAndSet(resolveLocalIp());
        String prevSsid = this.currentSsid;
        this.currentSsid = resolveCurrentSsid();
        this.lastNetworkChangeMs = System.currentTimeMillis();

        String newIp = currentLocalIp.get();
        Log.i(TAG, "Network change detected: IP "
                + prevIp + " → " + newIp
                + ", SSID: " + prevSsid + " → " + currentSsid);

        // Notify all listeners on the main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            for (NetworkChangeListener l : listeners) {
                try {
                    l.onNetworkChanged(newIp, prevSsid, currentSsid);
                } catch (Exception e) {
                    Log.e(TAG, "Listener threw during network change", e);
                }
            }
        });
    }

    // ---------------------------------------------------------------
    //  IP / SSID resolution
    // ---------------------------------------------------------------

    /**
     * Resolves the current local IPv4 address from the active
     * non-loopback network interface.
     */
    private static String resolveLocalIp() {
        try {
            Enumeration<NetworkInterface> ifaces =
                    NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;

            List<NetworkInterface> sorted = new ArrayList<>();
            while (ifaces.hasMoreElements()) {
                sorted.add(ifaces.nextElement());
            }
            // Prefer wlan interfaces
            Collections.sort(sorted, (a, b) -> {
            boolean aWifi = a.getName() != null && a.getName().toLowerCase(java.util.Locale.ROOT).contains("wlan");
            boolean bWifi = b.getName() != null && b.getName().toLowerCase(java.util.Locale.ROOT).contains("wlan");
                return Boolean.compare(bWifi, aWifi);
            });

            for (NetworkInterface iface : sorted) {
                if (iface.isLoopback() || !iface.isUp()) continue;

                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve local IP", e);
        }
        return null;
    }

    /**
     * Attempts to read the current Wi-Fi SSID (requires
     * {@code ACCESS_WIFI_STATE} permission).
     */
    private String resolveCurrentSsid() {
        try {
            WifiManager wifi = (WifiManager) appContext
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    // Android returns "<unknown ssid>" or "0x" when not connected
                    if (ssid != null && !ssid.contains("unknown") && !ssid.startsWith("0x")) {
                        // Strip surrounding quotes added by Android
                        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                            ssid = ssid.substring(1, ssid.length() - 1);
                        }
                        return ssid;
                    }
                }
            }
        } catch (SecurityException e) {
            // Missing ACCESS_WIFI_STATE permission
            Log.w(TAG, "Cannot resolve SSID: missing permission", e);
        } catch (Exception e) {
            Log.w(TAG, "Cannot resolve SSID", e);
        }
        return null;
    }
}
