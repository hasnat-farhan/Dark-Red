package com.antor.sosblue.bridge;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages real UDP broadcast-based mesh networking for local Wi-Fi peer
 * discovery and message delivery on the same LAN segment.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li>Acquires a {@link WifiManager.MulticastLock} before opening the
 *       UDP socket to ensure the device can receive multicast/broadcast
 *       packets even when the screen is off or the Wi-Fi radio is in
 *       power-save mode.</li>
 *   <li>Binds the receiver socket to {@code 0.0.0.0} (all local interfaces)
 *       on a configurable port so it hears packets on any active interface.</li>
 *   <li>Sets {@code SO_BROADCAST} and {@code SO_REUSEADDR} to survive
 *       rapid restarts and to enable broadcast sends.</li>
 *   <li>Provides a {@link #broadcast(String)} method for transmitting
 *       JSON payloads as UDP datagrams to the LAN broadcast address.</li>
 *   <li>Runs a dedicated daemon receiver thread that reads incoming
 *     datagrams and forwards them to {@link PacketListener#onUdpPacketReceived}.</li>
 * </ul>
 */
public class UdpMeshManager {

    private static final String TAG = "SOSBlueMesh";

    /** Default UDP port for SOSBlue mesh traffic. */
    public static final int DEFAULT_PORT = 41234;

    /** Maximum size of a single UDP datagram payload we accept (64 KB). */
    private static final int MAX_DATAGRAM_SIZE = 65507;

    private final Context appContext;
    private final int port;

    private WifiManager.MulticastLock multicastLock;
    private DatagramSocket socket;
    private final AtomicBoolean running;
    private Thread receiverThread;

    /** Callback for packets received from the network. */
    private volatile PacketListener listener;

    /** Single-thread executor for broadcast sends (avoids blocking caller). */
    private final ExecutorService sendExecutor;

    /**
     * Callback interface for delivering received UDP payloads to the
     * F2P bridge layer.
     */
    public interface PacketListener {
        /**
         * Invoked on the receiver thread when a complete UDP datagram arrives.
         *
         * @param payloadJson  the UTF-8 decoded payload string
         * @param sourceAddress dotted-decimal IP of the sender
         * @param sourcePort   UDP port of the sender
         */
        void onUdpPacketReceived(String payloadJson, String sourceAddress, int sourcePort);
    }

    // ---------------------------------------------------------------
    //  Construction
    // ---------------------------------------------------------------

    /**
     * @param context Any Android context (application context is stored).
     * @param port    UDP port to bind and broadcast on. Use {@link #DEFAULT_PORT}.
     */
    public UdpMeshManager(Context context, int port) {
        this.appContext = context.getApplicationContext();
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.running = new AtomicBoolean(false);
        this.sendExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "udp-mesh-send");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Convenience constructor using the default port.
     */
    public UdpMeshManager(Context context) {
        this(context, DEFAULT_PORT);
    }

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    /**
     * Acquires the MulticastLock, opens the UDP socket, binds to
     * {@code 0.0.0.0:port}, sets {@code SO_BROADCAST} and
     * {@code SO_REUSEADDR}, then starts the receiver thread.
     *
     * @param listener callback for incoming datagrams
     */
    public synchronized void start(PacketListener listener) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "start() called but already running");
            return;
        }
        this.listener = listener;

        // ── Step 1: Acquire MulticastLock ──────────────────────────
        acquireMulticastLock();

        // ── Step 2: Open & configure socket ────────────────────────
        try {
            socket = new DatagramSocket(null);
            socket.setBroadcast(true);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            Log.i(TAG, "UDP socket bound to 0.0.0.0:" + port
                    + " (broadcast=" + socket.getBroadcast()
                    + ", reuseAddr=" + socket.getReuseAddress() + ")");
        } catch (SocketException e) {
            Log.e(TAG, "Failed to create/bind UDP socket on port " + port, e);
            running.set(false);
            releaseMulticastLock();
            return;
        }

        // ── Step 3: Start receiver thread ──────────────────────────
        receiverThread = new Thread(this::receiverLoop, "udp-mesh-recv");
        receiverThread.setDaemon(true);
        receiverThread.start();
        Log.i(TAG, "UdpMeshManager started on port " + port);
    }

    /**
     * Stops the receiver thread, closes the socket, and releases the
     * MulticastLock.
     */
    public synchronized void stop() {
        if (!running.getAndSet(false)) return;

        // Interrupt the receiver thread so it exits the blocking read
        if (receiverThread != null) {
            receiverThread.interrupt();
        }

        // Close the socket (this also unblocks the receiver thread)
        if (socket != null && !socket.isClosed()) {
            socket.close();
            Log.d(TAG, "UDP socket closed");
        }

        // Release Wi-Fi lock
        releaseMulticastLock();

        // Shut down send executor
        sendExecutor.shutdownNow();

        receiverThread = null;
        socket = null;
        Log.i(TAG, "UdpMeshManager stopped");
    }

    // ---------------------------------------------------------------
    //  Send
    // ---------------------------------------------------------------

    /**
     * Serialises and broadcasts a JSON payload to the LAN subnet via UDP.
     * <p>
     * The send happens on a dedicated background thread so the caller
     * (typically the UI thread or the engine dispatch thread) is never
     * blocked by network I/O.
     * </p>
     *
     * @param payloadJson the JSON string to broadcast
     */
    public void broadcast(String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) return;
        final byte[] data = payloadJson.getBytes(StandardCharsets.UTF_8);

        if (data.length > MAX_DATAGRAM_SIZE) {
            Log.w(TAG, "Payload too large for UDP (" + data.length + " bytes, max "
                    + MAX_DATAGRAM_SIZE + ") — dropping");
            return;
        }

        sendExecutor.execute(() -> {
            if (socket == null || socket.isClosed()) {
                Log.w(TAG, "Cannot broadcast — socket is closed");
                return;
            }
            try {
                // Try the global broadcast address first
                InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
                DatagramPacket packet = new DatagramPacket(data, data.length,
                        broadcastAddr, port);
                socket.send(packet);
                Log.d(TAG, "Broadcast sent to 255.255.255.255:" + port
                        + " (" + data.length + " bytes)");

                // Also broadcast on each network interface's broadcast address
                // for devices on different subnets
                Enumeration<NetworkInterface> ifaces =
                        NetworkInterface.getNetworkInterfaces();
                if (ifaces != null) {
                    while (ifaces.hasMoreElements()) {
                        NetworkInterface iface = ifaces.nextElement();
                        if (iface.isLoopback() || !iface.isUp()) continue;
                        for (java.net.InterfaceAddress ifAddr : iface.getInterfaceAddresses()) {
                            InetAddress bc = ifAddr.getBroadcast();
                            if (bc != null && !bc.equals(broadcastAddr)) {
                                DatagramPacket pkt = new DatagramPacket(
                                        data, data.length, bc, port);
                                socket.send(pkt);
                                Log.v(TAG, "Broadcast also sent to "
                                        + bc.getHostAddress() + ":" + port);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Broadcast send failed", e);
            }
        });
    }

    /**
     * Returns true if the manager is actively listening for packets.
     */
    public boolean isRunning() {
        return running.get();
    }

    /** Returns the port this manager is bound to. */
    public int getPort() {
        return port;
    }

    // ---------------------------------------------------------------
    //  Private helpers
    // ---------------------------------------------------------------

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) appContext
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock(TAG);
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                Log.i(TAG, "MulticastLock acquired");
            } else {
                Log.w(TAG, "WifiManager not available — cannot acquire MulticastLock");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire MulticastLock", e);
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
                Log.d(TAG, "MulticastLock released");
            } catch (Exception e) {
                Log.e(TAG, "Failed to release MulticastLock", e);
            }
            multicastLock = null;
        }
    }

    /**
     * Receiver thread loop — blocks on {@link DatagramSocket#receive}
     * and forwards complete datagrams to {@link #listener}.
     */
    private void receiverLoop() {
        byte[] buffer = new byte[MAX_DATAGRAM_SIZE];
        Log.i(TAG, "Receiver thread started on port " + port);

        while (running.get()) {
            try {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                socket.receive(datagram);

                String payload = new String(datagram.getData(), 0, datagram.getLength(),
                        StandardCharsets.UTF_8);
                String sourceIp = datagram.getAddress().getHostAddress();
                int sourcePort = datagram.getPort();

                Log.d(TAG, "UDP packet received from " + sourceIp + ":"
                        + sourcePort + " (" + payload.length() + " chars)");

                PacketListener currentListener = this.listener;
                if (currentListener != null && !payload.isEmpty()) {
                    currentListener.onUdpPacketReceived(payload, sourceIp, sourcePort);
                }
            } catch (SocketException e) {
                if (!running.get()) {
                    Log.d(TAG, "Socket closed — receiver thread exiting");
                } else {
                    Log.e(TAG, "Socket error in receiver loop", e);
                }
                break;
            } catch (IOException e) {
                if (running.get()) {
                    Log.e(TAG, "I/O error in receiver loop", e);
                }
                break;
            }
        }
        Log.i(TAG, "Receiver thread exited");
    }


}
