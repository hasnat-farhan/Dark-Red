package com.antor.sosblue.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wi-Fi Direct (P2P) manager that provides fallback peer discovery and
 * direct device-to-device connections when devices are on different
 * Wi-Fi subnets where LAN broadcasts are blocked.
 *
 * <h3>Why Wi-Fi Direct?</h3>
 * When two devices are connected to different Wi-Fi networks (or one is
 * on cellular), local UDP broadcasts at {@code 255.255.255.255} cannot
 * reach across subnets. Wi-Fi Direct creates an ad-hoc P2P link that
 * bypasses the access point, allowing direct communication.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li>Uses {@link WifiP2pManager} to discover peers and form groups.</li>
 *   <li>Registers a {@link BroadcastReceiver} for Wi-Fi Direct intents
 *       ({@code WIFI_P2P_PEERS_CHANGED_ACTION}, etc.).</li>
 *   <li>When a P2P connection is established, exposes the group owner
 *       IP address so {@link UdpMeshManager#sendDirect} can route traffic
 *       over the P2P link.</li>
 *   <li>Falls back gracefully if Wi-Fi Direct is unavailable on the device.</li>
 * </ul>
 */
public class WifiDirectManager {

    private static final String TAG = "WifiDirectMgr";

    private final Context appContext;
    private final WifiP2pManager p2pManager;
    private final WifiP2pManager.Channel p2pChannel;
    private final AtomicBoolean initialized;
    private final AtomicBoolean discovering;
    private final AtomicReference<String> groupOwnerIp;
    private final CopyOnWriteArrayList<P2pPeerListener> peerListeners;
    private final CopyOnWriteArrayList<P2pConnectionListener> connectionListeners;

    private BroadcastReceiver p2pReceiver;
    private boolean receiverRegistered;
    private List<WifiP2pDevice> discoveredPeers;
    private WifiP2pDevice connectedDevice;

    // ---------------------------------------------------------------
    //  Listener interfaces
    // ---------------------------------------------------------------

    /** Callback for Wi-Fi Direct peer discovery results. */
    public interface P2pPeerListener {
        /** Invoked when a new peer is discovered via Wi-Fi Direct. */
        void onPeerDiscovered(String deviceName, String deviceAddress, String groupOwnerIp);
    }

    /** Callback for Wi-Fi Direct connection state changes. */
    public interface P2pConnectionListener {
        /**
         * Invoked when a P2P connection is established or lost.
         *
         * @param connected    true if connected, false if disconnected
         * @param groupOwnerIp the group owner's IP address (non-null when connected)
         */
        void onConnectionChanged(boolean connected, @Nullable String groupOwnerIp);
    }

    // ---------------------------------------------------------------
    //  Construction
    // ---------------------------------------------------------------

    public WifiDirectManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.p2pManager = (WifiP2pManager) appContext.getSystemService(Context.WIFI_P2P_SERVICE);
        this.initialized = new AtomicBoolean(false);
        this.discovering = new AtomicBoolean(false);
        this.groupOwnerIp = new AtomicReference<>(null);
        this.peerListeners = new CopyOnWriteArrayList<>();
        this.connectionListeners = new CopyOnWriteArrayList<>();
        this.discoveredPeers = new ArrayList<>();

        if (p2pManager != null) {
            this.p2pChannel = p2pManager.initialize(appContext, Looper.getMainLooper(), null);
            Log.i(TAG, "WifiP2pManager initialized");
        } else {
            this.p2pChannel = null;
            Log.w(TAG, "Wi-Fi Direct not available on this device");
        }
    }

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    /**
     * Initialises Wi-Fi Direct and registers the P2P broadcast receiver.
     */
    public synchronized void initialize() {
        if (initialized.getAndSet(true)) return;
        if (p2pManager == null || p2pChannel == null) {
            Log.w(TAG, "Cannot initialise — Wi-Fi Direct unavailable");
            return;
        }

        // Register the Wi-Fi Direct broadcast receiver
        p2pReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    Log.i(TAG, "Wi-Fi Direct state changed: "
                            + (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED ? "ENABLED" : "DISABLED"));
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    Log.d(TAG, "Wi-Fi Direct peers changed — requesting peer list");
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    if (networkInfo != null && networkInfo.isConnected()) {
                        Log.i(TAG, "Wi-Fi Direct connection established");
                        // Request connection info to get group owner IP
                        p2pManager.requestConnectionInfo(p2pChannel, info -> {
                            handleConnectionInfo(info);
                        });
                    } else {
                        Log.i(TAG, "Wi-Fi Direct connection lost");
                        groupOwnerIp.set(null);
                        notifyConnectionChanged(false, null);
                    }
                } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                    WifiP2pDevice device = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    if (device != null) {
                        Log.d(TAG, "This device: " + device.deviceName + " @ " + device.deviceAddress);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(p2pReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(p2pReceiver, filter);
        }
        receiverRegistered = true;

        Log.i(TAG, "Wi-Fi Direct initialized and receiver registered");
    }

    /**
     * Shuts down Wi-Fi Direct and unregisters the receiver.
     */
    public synchronized void shutdown() {
        initialized.set(false);
        stopDiscovery();

        if (receiverRegistered && p2pReceiver != null) {
            try {
                appContext.unregisterReceiver(p2pReceiver);
            } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }

        // Remove the P2P group if we are the owner
        if (p2pManager != null && p2pChannel != null) {
            try {
                p2pManager.removeGroup(p2pChannel, null);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove P2P group during shutdown: " + e.getMessage());
            }
        }

        groupOwnerIp.set(null);
        connectedDevice = null;
        discoveredPeers.clear();

        Log.i(TAG, "Wi-Fi Direct shut down");
    }

    // ---------------------------------------------------------------
    //  Discovery
    // ---------------------------------------------------------------

    /**
     * Starts Wi-Fi Direct peer discovery.
     * <p>
     * If broadcast-based discovery is not finding peers on the current
     * subnet, call this to initiate P2P discovery as a fallback.
     * </p>
     */
    public void startDiscovery() {
        if (p2pManager == null || p2pChannel == null) return;
        if (discovering.getAndSet(true)) return;

        try {
            p2pManager.discoverPeers(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct peer discovery started");
                }

                @Override
                public void onFailure(int reason) {
                    discovering.set(false);
                    Log.w(TAG, "Wi-Fi Direct discovery failed (reason=" + reason + ")");
                }
            });
        } catch (SecurityException e) {
            discovering.set(false);
            Log.w(TAG, "Wi-Fi Direct discovery blocked (missing runtime permission): "
                    + e.getMessage());
        } catch (Exception e) {
            discovering.set(false);
            Log.w(TAG, "Wi-Fi Direct discovery failed", e);
        }
    }

    /**
     * Stops Wi-Fi Direct peer discovery.
     */
    public void stopDiscovery() {
        if (!discovering.getAndSet(false)) return;
        if (p2pManager != null && p2pChannel != null) {
            p2pManager.stopPeerDiscovery(p2pChannel, null);
        }
    }

    /**
     * Connects to a discovered Wi-Fi Direct peer.
     *
     * @param deviceAddress the MAC address of the peer device to connect to
     */
    public void connectToPeer(String deviceAddress) {
        if (p2pManager == null || p2pChannel == null || deviceAddress == null) return;

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceAddress;
        // groupOwnerIntent: values 0–15 (0 = least likely, 15 = most likely to be GO)
        // We set 15 (strong preference) because we want to act as the group owner,
        // allowing the other device to connect to us without needing infrastructure.
        // groupOwnerIntent: direct field assignment works on all API levels (26+).
        // On API 28+ the field is deprecated in favor of WifiP2pConfig.Builder,
        // but direct field assignment still compiles and functions correctly on
        // every supported API level.
        config.groupOwnerIntent = 15;

        try {
            p2pManager.connect(p2pChannel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "P2P connect request sent to " + deviceAddress);
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "P2P connect failed (reason=" + reason + ") to " + deviceAddress);
                }
            });
        } catch (SecurityException e) {
            Log.w(TAG, "P2P connect blocked (missing permission): " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "P2P connect failed", e);
        }
    }

    /**
     * Disconnects from the current P2P group.
     */
    public void disconnect() {
        if (p2pManager != null && p2pChannel != null) {
            p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "P2P group removed");
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "Failed to remove P2P group (reason=" + reason + ")");
                }
            });
        }
        groupOwnerIp.set(null);
        connectedDevice = null;
    }

    // ---------------------------------------------------------------
    //  Listeners
    // ---------------------------------------------------------------

    public void addPeerListener(P2pPeerListener listener) {
        if (listener != null) peerListeners.add(listener);
    }

    public void removePeerListener(P2pPeerListener listener) {
        peerListeners.remove(listener);
    }

    public void addConnectionListener(P2pConnectionListener listener) {
        if (listener != null) connectionListeners.add(listener);
    }

    public void removeConnectionListener(P2pConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    // ---------------------------------------------------------------
    //  Public accessors
    // ---------------------------------------------------------------

    /**
     * Returns the group owner IP address if a P2P connection is active.
     */
    @Nullable
    public String getGroupOwnerIp() {
        return groupOwnerIp.get();
    }

    /** Returns true if Wi-Fi Direct is available on this device. */
    public boolean isAvailable() {
        return p2pManager != null;
    }

    /** Returns true if P2P discovery is currently running. */
    public boolean isDiscovering() {
        return discovering.get();
    }

    /** Returns the list of discovered P2P peers. */
    public List<WifiP2pDevice> getDiscoveredPeers() {
        return new ArrayList<>(discoveredPeers);
    }

    /** Returns the currently connected P2P device info, or null. */
    @Nullable
    public WifiP2pDevice getConnectedDevice() {
        return connectedDevice;
    }

    // ---------------------------------------------------------------
    //  Internal
    // ---------------------------------------------------------------

    /**
     * Requests the current peer list from the Wi-Fi Direct framework.
     */
    private void requestPeers() {
        if (p2pManager == null || p2pChannel == null) return;

        try {
            p2pManager.requestPeers(p2pChannel, (peers) -> {
                discoveredPeers.clear();
                if (peers != null) {
                    Collection<WifiP2pDevice> deviceList = peers.getDeviceList();
                    discoveredPeers.addAll(deviceList);
                    Log.d(TAG, "Discovered " + deviceList.size() + " P2P peers");

                    for (WifiP2pDevice device : deviceList) {
                        Log.i(TAG, "P2P peer: " + device.deviceName
                                + " (" + device.deviceAddress + "), status=" + device.status);
                        notifyPeerDiscovered(device);
                    }
                }
            });
        } catch (SecurityException e) {
            Log.w(TAG, "Failed to request P2P peers (missing permission): " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Failed to request P2P peers", e);
        }
    }

    /**
     * Handles the P2P connection info once established.
     * Extracts the group owner IP address and notifies listeners.
     */
    private void handleConnectionInfo(WifiP2pInfo info) {
        if (info == null) return;

        String goIp = null;
        if (info.groupOwnerAddress != null) {
            InetAddress addr = info.groupOwnerAddress;
            if (addr != null) {
                goIp = addr.getHostAddress();
            }
        }

        Log.i(TAG, "P2P connection info: groupOwner=" + info.isGroupOwner
                + ", goIp=" + goIp);

        groupOwnerIp.set(goIp);
        notifyConnectionChanged(true, goIp);
    }

    private void notifyPeerDiscovered(WifiP2pDevice device) {
        String goIp = groupOwnerIp.get();
        for (P2pPeerListener l : peerListeners) {
            try {
                l.onPeerDiscovered(device.deviceName, device.deviceAddress, goIp);
            } catch (Exception e) {
                Log.e(TAG, "Peer listener threw", e);
            }
        }
    }

    private void notifyConnectionChanged(boolean connected, @Nullable String ip) {
        for (P2pConnectionListener l : connectionListeners) {
            try {
                l.onConnectionChanged(connected, ip);
            } catch (Exception e) {
                Log.e(TAG, "Connection listener threw", e);
            }
        }
    }
}
