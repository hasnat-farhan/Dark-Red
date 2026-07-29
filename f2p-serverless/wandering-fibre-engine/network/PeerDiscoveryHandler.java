package com.antor.f2p.engine.network;

import com.antor.f2p.engine.core.FibreEngineStateMachine;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Real UDP-based peer discovery handler that broadcasts heartbeat beacons
 * and registers peers discovered via the Android-side UdpMeshManager.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li><b>Sending:</b> Opens its own {@link DatagramSocket} to broadcast
 *       JSON heartbeat beacons to the LAN broadcast address on a configurable
 *       port. Heartbeat format:
 *       {@code {"type":"peer_heartbeat","node_id":"...","phone":"...","timestamp":...}}</li>
 *   <li><b>Receiving:</b> Incoming heartbeats arrive via the Android-side
 *       {@code UdpMeshManager} (which holds the {@code MulticastLock}) and
 *       are fed into {@link #onHeartbeatReceived(String, String)} by
 *       {@code F2PBridge}. This method registers the peer and transitions
 *       the engine state to {@code CONNECTED_MESH}.</li>
 *   <li><b>Lifecycle:</b> Starts on engine initialize, stops on shutdown.</li>
 * </ul>
 */
public class PeerDiscoveryHandler {

    private static final Logger LOG = Logger.getLogger(PeerDiscoveryHandler.class.getName());

    /** Default interval between heartbeat broadcasts (ms). */
    public static final int DEFAULT_HEARTBEAT_INTERVAL_MS = 3000;
    /** Default UDP port for heartbeat broadcasts. */
    public static final int DEFAULT_DISCOVERY_PORT = 41234;

    private final int heartbeatIntervalMs;
    private final int port;
    private final String nodeId;
    private final String phoneNumber;
    private final PeerDiscovery peerDiscovery;

    private final AtomicBoolean running;
    private final AtomicBoolean meshFormed;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private DatagramSocket sendSocket;
    private FibreEngineStateMachine stateMachine;

    /**
     * Creates a minimal no-op handler for use as a default placeholder
     * before the real handler is configured via {@code F2PBridge}.
     * Does NOT open any sockets or send heartbeats.
     */
    public PeerDiscoveryHandler() {
        this(DEFAULT_HEARTBEAT_INTERVAL_MS, DEFAULT_DISCOVERY_PORT,
                "unknown", "", null);
    }

    /**
     * Creates a real UDP peer discovery handler.
     *
     * @param heartbeatIntervalMs interval between heartbeat broadcasts
     * @param port                UDP port for broadcasts (must match UdpMeshManager port)
     * @param nodeId              local node identifier
     * @param phoneNumber         local phone number for identification
     * @param peerDiscovery       peer registry to announce discovered nodes
     */
    public PeerDiscoveryHandler(int heartbeatIntervalMs,
                                 int port,
                                 String nodeId,
                                 String phoneNumber,
                                 PeerDiscovery peerDiscovery) {
        this.heartbeatIntervalMs = heartbeatIntervalMs > 0 ? heartbeatIntervalMs : DEFAULT_HEARTBEAT_INTERVAL_MS;
        this.port = port > 0 ? port : DEFAULT_DISCOVERY_PORT;
        this.nodeId = nodeId != null ? nodeId : "unknown";
        this.phoneNumber = phoneNumber != null ? phoneNumber : "";
        this.peerDiscovery = peerDiscovery;
        this.running = new AtomicBoolean(false);
        this.meshFormed = new AtomicBoolean(false);
    }

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    /**
     * Starts the handler: binds a UDP send socket and begins broadcasting
     * periodic heartbeat beacons.
     *
     * @param fsm the engine's state machine for lifecycle transitions
     */
    public void start(FibreEngineStateMachine fsm) {
        if (running.getAndSet(true)) {
            return;
        }
        this.stateMachine = fsm;

        // ── Open UDP send socket ───────────────────────────────────────
        try {
            sendSocket = new DatagramSocket();
            sendSocket.setBroadcast(true);
            sendSocket.setReuseAddress(true);
            LOG.info("UDP discovery send socket opened on ephemeral port");
        } catch (SocketException e) {
            LOG.warning("Failed to open UDP send socket for discovery: " + e.getMessage());
            sendSocket = null;
        }

        // ── Start heartbeat scheduler ──────────────────────────────────
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "peer-discovery-heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatFuture = scheduler.scheduleAtFixedRate(
                this::sendHeartbeat,
                500,                    // initial delay — give engine time to settle
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS
        );

        LOG.info("Real PeerDiscoveryHandler started (interval=" + heartbeatIntervalMs
                + "ms, port=" + port + ", node=" + nodeId + ")");

        // If no other peers are expected (standalone mode), transition
        // to CONNECTED_MESH immediately so the app is usable.
        // The peer list will populate as heartbeats arrive.
        transitionToMesh();
    }

    /** Stops the heartbeat scheduler and closes the send socket. */
    public void stop() {
        running.set(false);
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (sendSocket != null && !sendSocket.isClosed()) {
            sendSocket.close();
            sendSocket = null;
        }
        LOG.info("PeerDiscoveryHandler stopped");
    }

    // ---------------------------------------------------------------
    //  Heartbeat send
    // ---------------------------------------------------------------

    /**
     * Broadcasts a JSON heartbeat beacon to the LAN.
     */
    private void sendHeartbeat() {
        if (!running.get() || sendSocket == null) {
            return;
        }
        try {
            // Build heartbeat JSON
            String heartbeat = "{"
                    + "\"type\":\"peer_heartbeat\""
                    + ",\"node_id\":\"" + nodeId + "\""
                    + ",\"phone\":\"" + phoneNumber + "\""
                    + ",\"timestamp\":" + System.currentTimeMillis()
                    + "}";

            byte[] data = heartbeat.getBytes(StandardCharsets.UTF_8);
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(data, data.length,
                    broadcastAddr, port);
            sendSocket.send(packet);

            LOG.fine("Heartbeat sent to 255.255.255.255:" + port);
        } catch (Exception e) {
            LOG.fine("Failed to send heartbeat: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    //  Inbound heartbeat (called from F2PBridge via the engine)
    // ---------------------------------------------------------------

    /**
     * Called by the Android-side {@code F2PBridge} when a
     * {@code peer_heartbeat} packet is received via UdpMeshManager.
     * <p>
     * Registers the discovered peer and transitions the engine to
     * {@code CONNECTED_MESH} if not already formed.
     * </p>
     *
     * @param peerId   unique identifier of the discovered peer (node ID)
     * @param endpoint network endpoint in the form {@code "ip:port"}
     */
    public void onHeartbeatReceived(String peerId, String endpoint) {
        if (peerId == null || peerId.isEmpty()) return;

        // Register the peer (safe if peerDiscovery is null — default handler case)
        if (peerDiscovery != null) {
            peerDiscovery.announcePeer(peerId, endpoint);
        }
        LOG.info("Peer discovered via heartbeat: " + peerId + " @ " + endpoint);

        // Transition to mesh if not already formed
        if (!meshFormed.getAndSet(true)) {
            LOG.info("First peer discovered — transitioning to CONNECTED_MESH");
            transitionToMesh();
        }
    }

    /** Returns true if the handler has started. */
    public boolean isRunning() {
        return running.get();
    }

    /** Returns true if at least one peer has been discovered. */
    public boolean isMeshFormed() {
        return meshFormed.get();
    }

    // ---------------------------------------------------------------
    //  Internal
    // ---------------------------------------------------------------

    private void transitionToMesh() {
        if (stateMachine != null) {
            stateMachine.meshConnected();
        }
    }
}
