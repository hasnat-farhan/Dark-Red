package com.antor.sosblue.bridge;

import android.app.Application;
import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.antor.f2p.engine.api.EngineCallback;
import com.antor.f2p.engine.api.EngineConfig;
import com.antor.f2p.engine.api.EngineState;
import com.antor.f2p.engine.api.FibrePacket;
import com.antor.f2p.engine.api.FibreSignal;
import com.antor.f2p.engine.api.LogLevel;
import com.antor.f2p.engine.api.MeshHealthSnapshot;
import com.antor.f2p.engine.api.WanderingFibreEngine;
import com.antor.f2p.engine.network.PeerDiscoveryHandler;

import com.antor.sosblue.identity.F2PMessage;
import com.antor.sosblue.identity.MessageEncryptor;
import com.antor.sosblue.identity.UserIdentity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production-ready bridge adapter that integrates the Wandering Fibre Engine
 * into the SOSBlue Android application.
 * <p>
 * This is the <strong>only</strong> class in SOSBlue that imports from
 * {@code com.antor.f2p.engine.*}. All interaction with the engine happens
 * through the public {@code api} package — SOSBlue has zero coupling to
 * the internal {@code core} or {@code network} packages.
 * </p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #startEngine(EngineConfig)} — configure and initialise</li>
 *   <li>{@link #dispatchSignal(String, Map)} — send signals</li>
 *   <li>{@link #pauseRouting()} / {@link #resumeRouting()} — flow control</li>
 *   <li>{@link #getNetworkDiagnostics()} — health check</li>
 *   <li>{@link #stopEngine()} — graceful shutdown</li>
 * </ol>
 */
public class F2PBridge {

    private static final String TAG = "F2PBridge";

    private final WanderingFibreEngine engine;
    private final AtomicBoolean started;
    private final ExecutorService executor;

    /** Dedicated scheduler for periodic dedup-cache eviction. */
    private final ScheduledExecutorService dedupCleanup;

    /** Application context — avoids hidden API ActivityThread.currentApplication(). */
    private final Context appContext;

    /** UDP mesh manager for local Wi-Fi broadcast / receive. */
    private UdpMeshManager udpMeshManager;

    /** Network connectivity monitor — detects Wi-Fi switches, IP changes. */
    private NetworkConnectivityManager connectivityManager;

    /** Wi-Fi Direct manager — fallback when broadcasts don't cross subnets. */
    private WifiDirectManager wifiDirectManager;

    /**
     * SMS carrier for offline-last-resort delivery of F2P envelopes when no
     * mesh peers, Wi-Fi, or remote endpoints are reachable. Lazily created
     * on the first {@link TransportMode#SMS_FALLBACK} send (so phones without
     * telephony never allocate it) and tracked here so it's not garbage-collected
     * mid-conversation.
     */
    private SmsTransport smsTransport;

    /** Peer discovery listeners — notified when a peer is discovered or lost. */
    private final CopyOnWriteArrayList<PeerDiscoveryListener> peerDiscoveryListeners;

    /** Packet counter for logging. */
    private final AtomicInteger packetSequence;

    /**
     * Lightweight message-ID cache that prevents locally-sent messages
     * (received back via UDP loopback) from being re-processed.
     * Maps messageId → timestamp. Entries expire after 60 seconds.
     */
    private final ConcurrentHashMap<String, Long> seenMessageIds;

    /** Error-boundary status code: last non-fatal error or 0 if healthy. */
    private volatile int lastStatusCode;
    private volatile String lastStatusMessage;

    public F2PBridge(Context context) {
        this.appContext = context.getApplicationContext();
        this.engine = new WanderingFibreEngine();
        this.started = new AtomicBoolean(false);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "f2p-engine-init");
            t.setDaemon(true);
            return t;
        });
        this.lastStatusCode = 0;
        this.lastStatusMessage = "OK";
        this.packetSequence = new AtomicInteger(0);
        this.peerDiscoveryListeners = new CopyOnWriteArrayList<>();
        this.seenMessageIds = new ConcurrentHashMap<>();
        // Dedicated scheduler for periodic dedup-cache eviction.
        // Uses a SEPARATE thread so it never blocks the main executor.
        this.dedupCleanup = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "f2p-dedup-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.dedupCleanup.scheduleAtFixedRate(() -> {
            try {
                long cutoff = System.currentTimeMillis() - 60_000;
                seenMessageIds.values().removeIf(t -> t < cutoff);
            } catch (Throwable t) {
                Log.e(TAG, "Dedup cleanup task threw", t);
            }
        }, 30_000, 30_000, TimeUnit.MILLISECONDS);

        // ── Initialise network connectivity monitor ───────────────────
        this.connectivityManager = new NetworkConnectivityManager(appContext);

        // ── Initialise Wi-Fi Direct fallback ──────────────────────────
        this.wifiDirectManager = new WifiDirectManager(appContext);

    }

    // ---------------------------------------------------------------
    //  Peer discovery listener interface
    // ---------------------------------------------------------------

    /** Callback for real-time peer discovery events from UDP heartbeats. */
    public interface PeerDiscoveryListener {
        /**
         * Invoked when a heartbeat is received from a nearby device.
         *
         * @param nodeId    the peer's unique node identifier
         * @param username  the peer's display name (from UserIdentity)
         * @param phone     the peer's phone number (E.164)
         * @param ipAddress the peer's IP address from the UDP datagram
         * @param port      the peer's UDP port
         */
        void onPeerDiscovered(String nodeId, String username, String phone,
                               String ipAddress, int port);

        /** Invoked when a previously discovered peer has been evicted. */
        void onPeerLost(String nodeId);
    }

    public void addPeerDiscoveryListener(PeerDiscoveryListener listener) {
        if (listener != null) peerDiscoveryListeners.add(listener);
    }

    public void removePeerDiscoveryListener(PeerDiscoveryListener listener) {
        peerDiscoveryListeners.remove(listener);
    }

    // ---------------------------------------------------------------
    //  Phase 4 — Full Lifecycle
    // ---------------------------------------------------------------

    /**
     * Applies configuration and starts the engine.
     * <p>
     * Safe to call multiple times — subsequent calls are ignored if the
     * engine is already running.
     * </p>
     *
     * @param config engine configuration; uses defaults if null
     */
    public synchronized void startEngine(EngineConfig config) {
        if (started.getAndSet(true)) {
            Log.w(TAG, "startEngine() called but engine already started");
            return;
        }

        // Apply configuration before initialisation
        if (config != null) {
            engine.configure(config);
        }

        // ── Wire up real UDP PeerDiscoveryHandler ─────────────────────
        // Replace the default mock with one that sends real UDP heartbeats
        PeerDiscoveryHandler realHandler = createDiscoveryHandler(config);
        if (realHandler != null) {
            engine.setPeerDiscoveryHandler(realHandler);
        }

        // ── Start Wi-Fi Direct discovery alongside UDP for cross-subnet ─
        // This ensures we can discover devices even when client isolation
        // or AP isolation blocks UDP broadcasts on guest networks.
        // Note: On Android 10-12, this requires ACCESS_FINE_LOCATION at
        // runtime — the caller (ChatActivity) should have requested it.
        // We wrap in try-catch to handle devices where permission is missing.
        if (wifiDirectManager != null && wifiDirectManager.isAvailable()) {
            try {
                wifiDirectManager.startDiscovery();
                Log.i(TAG, "Wi-Fi Direct discovery started alongside UDP mesh");
            } catch (SecurityException e) {
                Log.w(TAG, "Wi-Fi Direct discovery not available (missing runtime permission): "
                        + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "Wi-Fi Direct discovery failed to start", e);
            }
        }

        // ── Start UDP mesh manager (for receiving) ────────────────────
        startUdpMesh();

        // ── Register network connectivity monitor ─────────────────────
        // Listens for Wi-Fi network changes and re-binds the UDP socket
        // so that messages continue flowing after a network switch.
        connectivityManager.addListener(
                (newLocalIp, prevSsid, currentSsid) -> handleNetworkChange(newLocalIp));
        connectivityManager.register();

        // ── Initialise Wi-Fi Direct fallback ──────────────────────────
        // When devices are on different subnets where LAN broadcasts
        // are blocked, Wi-Fi Direct provides a direct P2P link.
        wifiDirectManager.addConnectionListener((connected, groupOwnerIp) -> {
            if (connected && groupOwnerIp != null) {
                Log.i(TAG, "Wi-Fi Direct connected, GO IP: " + groupOwnerIp);
                // The group owner IP can be used to route messages directly
                // across different Wi-Fi subnets.
            }
        });
        wifiDirectManager.initialize();

        // ── Register a post-rebind listener to re-sync peer endpoints ─
        // After the UDP socket is re-created on network change, we need
        // to re-register the packet listener with the new socket.
        udpMeshManager.addRebindListener(() -> {
            Log.i(TAG, "Post-rebind: re-syncing peer discovery state");
            // Clear the engine-level peer endpoints so they get re-discovered
            engine.getPeerDiscovery().clearAllEndpoints();
            // Start Wi-Fi Direct discovery as a fallback
            wifiDirectManager.startDiscovery();
        });

        // Register the default logging listener
        engine.registerListener(new EngineCallback() {
            @Override
            public void onSignal(FibreSignal signal) {
                Log.d(TAG, "Signal: " + signal);
            }

            @Override
            public void onPacketReceived(FibrePacket packet) {
                Log.d(TAG, "Packet: " + packet);
            }

            @Override
            public void onStateChanged(String newState) {
                Log.i(TAG, "State → " + newState);
            }

            @Override
            public void onStateChanged(EngineState previous, EngineState current) {
                Log.i(TAG, "State: " + previous + " → " + current);
            }

            @Override
            public void onError(Throwable throwable) {
                Log.e(TAG, "Engine error", throwable);
            }

            @Override
            public void onEngineError(int statusCode, String message, Throwable cause) {
                lastStatusCode = statusCode;
                lastStatusMessage = message;
                Log.e(TAG, "Engine error [" + statusCode + "]: " + message, cause);
            }

            @Override
            public void onDiagnostics(String diagnostics) {
                Log.d(TAG, "Diagnostics:\n" + diagnostics);
            }
        });

        // Wrap initialise in an error boundary
        try {
            engine.initialize();
            Log.i(TAG, "Wandering Fibre Engine started successfully");
            Log.i(TAG, "Mesh UDP listener active on port "
                    + (udpMeshManager != null ? udpMeshManager.getPort() : "N/A"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to start engine", e);
            lastStatusCode = 1;
            lastStatusMessage = "Engine init failed: " + e.getMessage();
            started.set(false);
        }
    }

    /**
     * Gracefully stops the engine and releases all resources.
     */
    public synchronized void stopEngine() {
        if (!started.getAndSet(false)) return;
        // ── Stop network monitors first ──────────────────────────────
        if (connectivityManager != null) {
            connectivityManager.unregister();
        }
        if (wifiDirectManager != null) {
            wifiDirectManager.shutdown();
        }
        // ── Stop UDP mesh ────────────────────────────────────────────
        stopUdpMesh();
        try {
            engine.shutdown();
            // NOTE: executor and dedupCleanup are deliberately NOT shut down
            // here because they are final fields and cannot be recreated.
            // Calling shutdownNow() would prevent future startEngineAsync()
            // calls from ever executing — a RejectedExecutionException would
            // be silently swallowed and the engine would never restart.
            // These executors use daemon threads, so they are cleaned up
            // when the process exits. Any stale tasks still in the queue
            // will execute harmlessly because:
            //   - startEngine() is synchronized + guarded by started.getAndSet()
            //   - sendMessageAsync() is a no-op on a stopped engine
            //   - dedupCleanup.scheduleAtFixedRate just cleans the dedup cache
            Log.i(TAG, "Engine stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error during engine shutdown", e);
            lastStatusCode = 2;
            lastStatusMessage = "Shutdown error: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------
    //  Real UDP PeerDiscoveryHandler factory
    // ---------------------------------------------------------------

    /**
     * Creates a real UDP {@link PeerDiscoveryHandler} that broadcasts
     * heartbeat beacons on the same port as the UdpMeshManager.
     * Returns null if essential identity information is missing.
     */
    @androidx.annotation.Nullable
    private PeerDiscoveryHandler createDiscoveryHandler(EngineConfig config) {
        try {
            String localPhone = UserIdentity.getPhoneNumber(appContext);
            String nodeId = config != null ? config.getNodeId() : engine.getLocalNodeId();

            if (nodeId == null) {
                Log.w(TAG, "Cannot create real PeerDiscoveryHandler — nodeId is null");
                return null;
            }

            int discoveryPort = config != null ? config.getDiscoveryPort()
                    : com.antor.sosblue.bridge.UdpMeshManager.DEFAULT_PORT;
            int intervalMs = config != null ? config.getHeartbeatIntervalMs() : 3000;

            String localUsername = UserIdentity.getUsername(appContext);
            PeerDiscoveryHandler handler = new PeerDiscoveryHandler(
                    intervalMs,
                    discoveryPort,
                    nodeId,
                    localPhone != null ? localPhone : "",
                    localUsername != null ? localUsername : "",
                    engine.getPeerDiscovery()
            );

            Log.i(TAG, "Real UDP PeerDiscoveryHandler created (port=" + discoveryPort
                    + ", interval=" + intervalMs + "ms, node=" + nodeId + ")");
            return handler;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create PeerDiscoveryHandler", e);
            return null;
        }
    }

    // ---------------------------------------------------------------
    //  Flow control
    // ---------------------------------------------------------------

    /** Pauses packet routing. Queued signals are persisted locally. */
    public void pauseRouting() {
        engine.pauseRouting();
        Log.i(TAG, "Routing paused");
    }

    /** Resumes packet routing. Queued packets are replayed. */
    public void resumeRouting() {
        engine.resumeRouting();
        Log.i(TAG, "Routing resumed");
    }

    /** Returns true if the engine is currently paused. */
    public boolean isPaused() {
        return engine.isPaused();
    }

    // ---------------------------------------------------------------
    //  Diagnostics
    // ---------------------------------------------------------------

    /**
     * Returns a human-readable network diagnostics string.
     * Can be called at any time, even before {@link #startEngine(EngineConfig)}.
     */
    public String getNetworkDiagnostics() {
        return engine.getNetworkDiagnostics();
    }

    /**
     * Returns the last error-boundary status code.
     * <p>
     * 0 = healthy, 1 = init failure, 2 = shutdown error,
     * 100+ = crypto/subsystem errors, 200+ = listener errors.
     * </p>
     */
    public int getLastStatusCode() {
        return lastStatusCode;
    }

    /** Returns the last error-boundary status message. */
    public String getLastStatusMessage() {
        return lastStatusMessage;
    }

    /** Resets the error-boundary status to healthy (0 / "OK"). */
    public void clearStatus() {
        lastStatusCode = 0;
        lastStatusMessage = "OK";
    }

    // ---------------------------------------------------------------
    //  Asynchronous engine startup (off UI thread)
    // ---------------------------------------------------------------

    /** Callback for {@link #startEngineAsync(EngineConfig, OnEngineStartListener)}. */
    public interface OnEngineStartListener {
        /** Invoked on the <b>main thread</b> once the engine initialises successfully. */
        void onEngineStarted();
        /** Invoked on the <b>main thread</b> if engine initialisation failed. */
        void onEngineError(int statusCode, String message);
    }

    /**
     * Starts the engine on a dedicated background thread so the calling
     * (typically UI) thread is never blocked.
     * <p>The listener callbacks are posted to the Android main looper.</p>
     */
    public void startEngineAsync(@androidx.annotation.Nullable EngineConfig config,
                                  @androidx.annotation.Nullable OnEngineStartListener listener) {
        executor.execute(() -> {
            startEngine(config);
            android.os.Handler mainHandler = new android.os.Handler(
                    android.os.Looper.getMainLooper());
            if (listener != null) {
                if (started.get()) {
                    mainHandler.post(listener::onEngineStarted);
                } else {
                    mainHandler.post(() ->
                            listener.onEngineError(lastStatusCode, lastStatusMessage));
                }
            }
        });
    }

    // ---------------------------------------------------------------
    //  Network change handler
    // ---------------------------------------------------------------

    /**
     * Handles a detected network change (Wi-Fi switch, IP change,
     * disconnection). Re-binds the UDP socket, clears stale peer
     * targets, and starts Wi-Fi Direct fallback discovery.
     */
    private void handleNetworkChange(String newLocalIp) {
        Log.i(TAG, "Network change detected — local IP: " + newLocalIp
                + ", re-binding UDP socket");

        // 1. Re-bind the UDP socket and clear stale peer endpoints
        if (udpMeshManager != null) {
            udpMeshManager.rebindAfterNetworkChange();
        }

        // 2. Clear the dedup cache so messages from the new network
        //    are not incorrectly dropped as duplicates
        seenMessageIds.clear();
        Log.d(TAG, "Cleared message-ID dedup cache after network change");

        // 3. Start Wi-Fi Direct discovery as a fallback since
        //    broadcast addresses may have changed
        if (wifiDirectManager != null && wifiDirectManager.isAvailable()) {
            wifiDirectManager.startDiscovery();
            Log.i(TAG, "Started Wi-Fi Direct fallback discovery");
        }

        Log.i(TAG, "Network change handled — ready on new network");
    }

    // ---------------------------------------------------------------
    //  UDP Mesh Management
    // ---------------------------------------------------------------

    /** Starts the UDP mesh manager and registers the packet callback. */
    private void startUdpMesh() {
        try {
            udpMeshManager = new UdpMeshManager(appContext);
            udpMeshManager.start((payloadJson, sourceAddress, sourcePort) -> {
                int seq = packetSequence.incrementAndGet();
                Log.d(TAG, "[" + seq + "] UDP packet received from "
                        + sourceAddress + ":" + sourcePort + " ("
                        + payloadJson.length() + " chars)");

                try {
                    // ── Check if this is a peer heartbeat ────────────────
                    // NOTE: Message deduplication is handled by
                    // FibreProcessor.processInboundPacket() which is the single
                    // chokepoint where ALL packets converge (direct UDP + relay).
                    // We do NOT deduplicate here because it would prevent the
                    // TTL relay from working correctly.
                    String packetType = com.antor.sosblue.identity.JsonPayloadHelper
                            .extractField(payloadJson, "type");

                    if ("peer_heartbeat".equals(packetType)) {
                        String peerNodeId = com.antor.sosblue.identity.JsonPayloadHelper
                                .extractField(payloadJson, "node_id");
                        String peerPhone = com.antor.sosblue.identity.JsonPayloadHelper
                                .extractField(payloadJson, "phone");
                        String peerUsername = com.antor.sosblue.identity.JsonPayloadHelper
                                .extractField(payloadJson, "username");

                        if (peerNodeId != null) {
                            String endpoint = sourceAddress + ":" + sourcePort;
                            Log.d(TAG, "[" + seq + "] Heartbeat from node="
                                    + peerNodeId + ", phone=" + peerPhone
                                    + ", user=" + peerUsername
                                    + " @ " + endpoint);

                            // Ignore our own heartbeats
                            String localPhone = UserIdentity.getPhoneNumber(appContext);
                            String normalizedLocal = UserIdentity.normalizePhoneNumber(localPhone);
                            String normalizedPeer = UserIdentity.normalizePhoneNumber(peerPhone);
                            if (normalizedLocal != null && normalizedLocal.equals(normalizedPeer)) {
                                Log.v(TAG, "[" + seq + "] Ignoring our own heartbeat");
                                return;
                            }

                            // Track this peer's dynamic IP endpoint so we can
                            // send direct ACKs (not just broadcasts) back to them.
                            if (udpMeshManager != null) {
                                udpMeshManager.updatePeerEndpoint(
                                        peerNodeId, sourceAddress, sourcePort);

                                // Also index by normalized phone so
                                // sendDirectToRecipient() can find the endpoint
                                // even before any message has been exchanged.
                                if (peerPhone != null) {
                                    String normalizedPeerPhone =
                                            UserIdentity.normalizePhoneNumber(peerPhone);
                                    if (normalizedPeerPhone != null) {
                                        udpMeshManager.updatePeerEndpoint(
                                                normalizedPeerPhone, sourceAddress, sourcePort);
                                    }
                                }
                            }

                            // Register peer via the engine's handler
                            engine.getPeerDiscoveryHandler().onHeartbeatReceived(
                                    peerNodeId, endpoint);
                            Log.i(TAG, "[" + seq + "] Peer discovered: " + peerNodeId
                                    + " (" + (peerUsername != null ? peerUsername : "?") + ")");

                            // Notify UI listeners so the peer list updates in real time
                            notifyPeerDiscovered(peerNodeId, peerUsername, peerPhone,
                                    sourceAddress, sourcePort);
                        } else {
                            Log.w(TAG, "[" + seq + "] Heartbeat missing node_id");
                        }
                        return;
                    }

                    // ── Parse received JSON fields and rebuild proper signal ──
                    String messageId = com.antor.sosblue.identity.JsonPayloadHelper
                            .extractField(payloadJson, "message_id");
                    String recipientPhone = com.antor.sosblue.identity.JsonPayloadHelper
                            .extractField(payloadJson, "recipient_phone");
                    String senderPhone = com.antor.sosblue.identity.JsonPayloadHelper
                            .extractField(payloadJson, "sender_phone");
                    String envelopeB64 = com.antor.sosblue.identity.JsonPayloadHelper
                            .extractField(payloadJson, "f2p_envelope");
                    String transport = com.antor.sosblue.identity.JsonPayloadHelper
                            .extractField(payloadJson, "transport");

                    // ── Message-ID dedup (self-loop prevention) ──────
                    // Drop any packet whose messageId is already in our local cache.
                    // This catches our own broadcasts received via UDP loopback AND
                    // protects against duplicate delivery from multi-hop relay paths
                    // that bypass the engine-level MessageDeduplicator.
                    if (messageId != null) {
                        Long prev = seenMessageIds.putIfAbsent(messageId, System.currentTimeMillis());
                        if (prev != null) {
                            Log.v(TAG, "[" + seq + "] Duplicate messageId=" + messageId
                                    + " — dropping (already seen)");
                            return;
                        }
                    }

                    // ── Ignore our own broadcasts to prevent self-loop ──
                    // Since UDP sockets receive their own broadcasts, we must
                    // filter out messages where the sender matches our phone.
                    if (senderPhone != null) {
                        String localPhone = UserIdentity.getPhoneNumber(appContext);
                        String normalizedLocal = UserIdentity.normalizePhoneNumber(localPhone);
                        String normalizedSender = UserIdentity.normalizePhoneNumber(senderPhone);
                        if (normalizedLocal != null && normalizedLocal.equals(normalizedSender)) {
                            Log.v(TAG, "[" + seq + "] Ignoring our own broadcast (sender match)");
                            return;
                        }
                    }

                    // ── Track sender's dynamic IP endpoint ──────────
                    // Store the source IP:port so we can respond with
                    // direct ACK packets instead of relying on broadcast.
                    // This is critical when the sender is on a different
                    // subnet or when the network renumbering occurs.
                    if (senderPhone != null && udpMeshManager != null) {
                        String normalizedSender = UserIdentity.normalizePhoneNumber(senderPhone);
                        if (normalizedSender != null) {
                            udpMeshManager.updatePeerEndpoint(
                                    normalizedSender, sourceAddress, sourcePort);
                        }
                    }

                    if (recipientPhone != null && envelopeB64 != null) {
                        HashMap<String, Object> fields = new HashMap<>();
                        fields.put("_destination", recipientPhone);
                        fields.put("sender_phone", senderPhone != null ? senderPhone : "");
                        fields.put("recipient_phone", recipientPhone);
                        fields.put("f2p_envelope", envelopeB64);
                        fields.put("transport", transport != null ? transport : "f2p");

                        // ── Determine if this message is for us ─────────
                        // Only send ACK if we are the intended final recipient.
                        // If we're just a relay node, we do NOT ACK (the final
                        // recipient will handle that).
                        String localPhone = UserIdentity.getPhoneNumber(appContext);
                        String normalizedLocal = UserIdentity.normalizePhoneNumber(localPhone);
                        String normalizedRecipient = UserIdentity.normalizePhoneNumber(recipientPhone);
                        boolean isForLocalDevice = normalizedLocal != null
                                && normalizedRecipient != null
                                && normalizedRecipient.equals(normalizedLocal);

                        if (isForLocalDevice) {
                            // ── Dispatch ACK back to sender ────────────────
                            // Send an acknowledgment directly to the sender's
                            // dynamic IP (not broadcast) so they know the message
                            // was received. We only do this when we are the
                            // intended final recipient.
                            sendMessageAck(messageId, senderPhone, sourceAddress, sourcePort);
                        }

                        // ── Also include media chunk fields if present ──
                        String transferId = com.antor.sosblue.identity.JsonPayloadHelper
                                .extractField(payloadJson, "transfer_id");
                        if (transferId != null) {
                            fields.put("transfer_id", transferId);
                            fields.put("chunk_index",
                                    com.antor.sosblue.identity.JsonPayloadHelper
                                            .extractField(payloadJson, "chunk_index"));
                            fields.put("total_chunks",
                                    com.antor.sosblue.identity.JsonPayloadHelper
                                            .extractField(payloadJson, "total_chunks"));
                            fields.put("file_name",
                                    com.antor.sosblue.identity.JsonPayloadHelper
                                            .extractField(payloadJson, "file_name"));
                            fields.put("mime_type",
                                    com.antor.sosblue.identity.JsonPayloadHelper
                                            .extractField(payloadJson, "mime_type"));
                            fields.put("content_type",
                                    com.antor.sosblue.identity.JsonPayloadHelper
                                            .extractField(payloadJson, "content_type"));
                            fields.put("caption",
                                    com.antor.sosblue.identity.JsonPayloadHelper
                                            .extractField(payloadJson, "caption"));
                            Log.d(TAG, "[" + seq + "] Incoming media chunk for transfer "
                                    + transferId);
                        }

                        engine.dispatchSignal("chat_message", fields);
                        Log.d(TAG, "[" + seq + "] Dispatched as chat_message to "
                                + recipientPhone);

                        // ── Multi-hop relay: re-broadcast with decremented TTL ──
                        // If this message is NOT addressed to us and has TTL > 1,
                        // decrement TTL and re-broadcast over UDP so other mesh
                        // nodes can receive it. The dedup cache (seenMessageIds)
                        // prevents infinite loops — any re-received copy will be
                        // dropped as already seen.
                        if (!isForLocalDevice && udpMeshManager != null) {
                            String ttlStr = com.antor.sosblue.identity.JsonPayloadHelper
                                    .extractField(payloadJson, "ttl");
                            if (ttlStr != null) {
                                try {
                                    int ttl = Integer.parseInt(ttlStr.trim());
                                    if (ttl > 1) {
                                        String relayPayload = payloadJson.replaceFirst(
                                                "\"ttl\"\\s*:\\s*\"" + ttl + "\"",
                                                "\"ttl\":\"" + (ttl - 1) + "\"");
                                        udpMeshManager.broadcast(relayPayload);
                                        Log.d(TAG, "[" + seq + "] Relayed with TTL="
                                                + (ttl - 1) + " for " + normalizedRecipient);
                                    }
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    } else {
                        Log.w(TAG, "[" + seq + "] Received UDP packet missing"
                                + " recipient_phone or f2p_envelope — dropping");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[" + seq + "] Failed to process incoming UDP packet", e);
                }
            });
            Log.i(TAG, "UDP mesh started on port " + udpMeshManager.getPort());
        } catch (Exception e) {
            Log.e(TAG, "Failed to start UDP mesh", e);
            udpMeshManager = null;
        }
    }

    /** Stops the UDP mesh manager if running. */
    private void stopUdpMesh() {
        if (udpMeshManager != null) {
            udpMeshManager.stop();
            udpMeshManager = null;
            Log.d(TAG, "UDP mesh stopped");
        }
    }

    // ---------------------------------------------------------------
    //  Signal dispatch
  // ---------------------------------------------------------------

    public void dispatchSignal(String type, Map<String, Object> payload) {
        engine.dispatchSignal(type, payload);
    }

    public void dispatchSignal(FibreSignal signal) {
        engine.dispatchSignal(signal);
    }

    // ---------------------------------------------------------------
    //  Multi-Transport sendMessage
    // ---------------------------------------------------------------

    /**
     * Routes a message through the selected transport mode.
     * <p>
     * For F2P_SERVERLESS mode, the message is encrypted using the recipient's
     * phone-derived key and wrapped in an {@link F2PMessage} envelope with
     * sender/recipient phone numbers for targeted routing.
     * </p>
     *
     * @param messageText  the text to send
     * @param recipientId  destination node ID (phone number for F2P mode)
     * @param selectedMode which transport to use (SOSBLUE_MESH or F2P_SERVERLESS)
     * @return {@code true} if the message was accepted for dispatch
     */
    public boolean sendMessage(String messageText, String recipientId,
                               TransportMode selectedMode) {
        switch (selectedMode) {
            case SOSBLUE_MESH: {
                dispatchSignal("chat_message", Map.of(
                        "recipient", recipientId,
                        "text", messageText,
                        "transport", "mesh"
                ));
                Log.d(TAG, "sendMessage → SOSBLUE_MESH to " + recipientId);
                return true;
            }
            case F2P_SERVERLESS: {
                if (!isRouting()) {
                    Log.w(TAG, "F2P_SERVERLESS selected but engine not routing — queuing offline");
                }

                // ── F2P Identity & Encryption ────────────────────────
                // Encrypt the message payload using the recipient's phone-derived key
                String senderPhone = UserIdentity.getPhoneNumber(appContext);
                String recipientPhone = UserIdentity.normalizePhoneNumber(recipientId);

                if (senderPhone == null || recipientPhone == null) {
                    Log.e(TAG, "Cannot encrypt — sender or recipient phone is null");
                    return false;
                }

                Log.d(TAG, "Sending F2P message: sender=" + senderPhone
                        + ", recipient=" + recipientPhone
                        + ", normalized=" + recipientPhone);

                // Encrypt the plaintext with recipient's phone-derived key
                byte[] encryptedPayload = MessageEncryptor.encrypt(recipientPhone, messageText);
                byte[] nonce = MessageEncryptor.generateNonce();

                // Build the F2P message envelope
                F2PMessage f2pMsg = new F2PMessage(
                        senderPhone,
                        recipientPhone,
                        encryptedPayload,
                        System.currentTimeMillis(),
                        nonce
                );

                // ── Generate a globally unique message ID ─────────────
                // This ID travels with the payload both through the engine
                // (in the signal) and over UDP (in the broadcast JSON).
                // Receiving devices use it to deduplicate across paths.
                String messageId = UUID.randomUUID().toString();

                // Pack the envelope into a signal for the engine
                // The _destination key tells FibreProcessor where to route
                // Build the JSON payload string for direct UDP broadcast
                String jsonPayload = buildF2pPayload(senderPhone, recipientPhone, f2pMsg, messageId);

                dispatchSignal("chat_message", Map.of(
                        "_destination", recipientPhone,
                        "sender_phone", senderPhone,
                        "recipient_phone", recipientPhone,
                        "f2p_envelope", Base64.encodeToString(f2pMsg.serialize(), Base64.NO_WRAP),
                        "transport", "f2p",
                        "ttl", "3",
                        "message_id", messageId
                ));

                // ── Also broadcast over local UDP mesh ─────────────────
                if (udpMeshManager != null && udpMeshManager.isRunning()) {
                    udpMeshManager.broadcast(jsonPayload);

                    // ── Also unicast directly to recipient's last-known IP ──
                    // This bypasses AP isolation on guest networks where
                    // broadcasts from guest → main are blocked, but the
                    // incoming datagram from the main-network device gave
                    // us its real IP:port.
                    sendDirectToRecipient(jsonPayload, recipientPhone);

                    Log.d(TAG, "Message also broadcast via UDP mesh");
                }

                Log.d(TAG, "sendMessage → F2P_SERVERLESS (encrypted) to " + recipientPhone);
                return true;
            }
            case SMS_FALLBACK: {
                // ── Offline-last-resort: SMS carrier ─────────────────
                // No mesh, no Wi-Fi, no remote — fall back to SMS so the
                // recipient's phone still receives the F2P envelope as a
                // multipart SMS. The receiver re-assembles and routes it
                // back through the standard listener pipeline.
                if (!TransportMode.SMS_FALLBACK.isAvailable(appContext)) {
                    Log.w(TAG, "SMS_FALLBACK selected but device lacks telephony");
                    return false;
                }

                String senderPhone = UserIdentity.getPhoneNumber(appContext);
                String recipientPhone = UserIdentity.normalizePhoneNumber(recipientId);

                if (senderPhone == null || recipientPhone == null) {
                    Log.e(TAG, "Cannot send SMS — sender or recipient phone is null");
                    return false;
                }

                // Encrypt with the recipient's phone-derived key — same crypto
                // as F2P_SERVERLESS, so receivers can decrypt uniformly.
                byte[] encryptedPayload = MessageEncryptor.encrypt(recipientPhone, messageText);
                byte[] nonce = MessageEncryptor.generateNonce();

                F2PMessage f2pMsg = new F2PMessage(
                        senderPhone,
                        recipientPhone,
                        encryptedPayload,
                        System.currentTimeMillis(),
                        nonce
                );

                if (smsTransport == null) {
                    smsTransport = new SmsTransport(appContext);
                }

                final String finalRecipient = recipientPhone;
                smsTransport.sendEnvelope(f2pMsg, new SmsTransport.OnSmsSendListener() {
                    @Override
                    public void onSent() {
                        Log.d(TAG, "SMS sent to " + finalRecipient);
                    }
                    @Override
                    public void onFailed(String reason) {
                        Log.e(TAG, "SMS send failed to " + finalRecipient + ": " + reason);
                        // Surface synchronous failures (no telephony, no
                        // sender phone, SecurityException) to the UI so
                        // the user gets feedback without having to read
                        // logcat. The listener runs on the main thread.
                        notifySmsError(reason);
                    }
                });

                Log.d(TAG, "sendMessage → SMS_FALLBACK to " + recipientPhone);
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * Returns the live {@link SmsTransport} for this bridge, or {@code null}
     * if no SMS send has occurred yet. Activities may use it to attach
     * envelope listeners for inbound SMS routing.
     */
    public SmsTransport getSmsTransport() {
        return smsTransport;
    }

    /**
     * Sets the active {@link SmsTransport}. Used by the Activity to
     * pre-allocate + register the receiver before the first SMS send
     * (so inbound envelopes are caught even if the user never explicitly
     * picked SMS as the transport). Replaces any existing instance.
     */
    public void setSmsTransport(SmsTransport transport) {
        this.smsTransport = transport;
    }

    // ---------------------------------------------------------------
    //  SMS error listener — surfaces send failures to the UI
    // ---------------------------------------------------------------

    /**
     * Callback invoked on the <b>main thread</b> when a synchronous
     * SMS send failure occurs (missing telephony, missing sender
     * phone, SecurityException, or other {@code sendEnvelope}
     * exception). The Activity uses this to show a Snackbar so the
     * user gets feedback without needing to read logcat.
     */
    public interface OnSmsErrorListener {
        void onSmsError(String reason);
    }

    private final CopyOnWriteArrayList<OnSmsErrorListener> smsErrorListeners =
            new CopyOnWriteArrayList<>();

    public void addSmsErrorListener(OnSmsErrorListener listener) {
        if (listener != null) smsErrorListeners.add(listener);
    }

    public void removeSmsErrorListener(OnSmsErrorListener listener) {
        smsErrorListeners.remove(listener);
    }

    private void notifySmsError(String reason) {
        if (smsErrorListeners.isEmpty()) return;
        for (OnSmsErrorListener l : smsErrorListeners) {
            try {
                l.onSmsError(reason);
            } catch (Exception e) {
                Log.w(TAG, "OnSmsErrorListener threw", e);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Multi-Transport sendMessage (async, off UI thread)
    // ---------------------------------------------------------------

    /**
     * Callback for async message send operations, providing success/failure
     * feedback so the UI can react accordingly (hide spinner, show errors).
     */
    public interface OnMessageSendListener {
        /** Invoked on the <b>main thread</b> when the message was accepted. */
        void onSent();
        /** Invoked on the <b>main thread</b> when the message could not be sent. */
        void onSendFailed(String reason);
    }

    /**
     * Sends a message on the background executor and invokes the appropriate
     * listener callback on the main thread depending on success or failure.
     */
    public void sendMessageAsync(String messageText, String recipientId,
                                  TransportMode selectedMode,
                                  @androidx.annotation.Nullable OnMessageSendListener listener) {
        executor.execute(() -> {
            boolean success = false;
            String errorReason = "Unknown error";
            try {
                success = sendMessage(messageText, recipientId, selectedMode);
                if (!success) {
                    errorReason = "Sender or recipient phone number is missing";
                } else {
                    Log.d(TAG, "Message dispatched via " + selectedMode.name()
                            + " to " + recipientId);
                }
            } catch (Exception e) {
                Log.e(TAG, "sendMessage failed", e);
                errorReason = e.getMessage() != null ? e.getMessage() : "Send failed: " + e.getClass().getSimpleName();
            }
            final boolean result = success;
            final String reason = errorReason;
            if (listener != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (result) {
                        listener.onSent();
                    } else {
                        listener.onSendFailed(reason);
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------
    //  Multi-Transport sendMedia (async, chunked)
    // ---------------------------------------------------------------

    /**
     * Sends a media file (image/video) through the mesh, splitting it
     * into encrypted chunks and dispatching each sequentially.
     *
     * @param context      Android context for URI access
     * @param mediaUri     content URI of the media file
     * @param recipientId  destination phone number (F2P mode)
     * @param selectedMode transport mode
     * @param caption      optional text caption (can be empty)
     * @param listener     progress/completion callback (main thread)
     */
    public void sendMediaAsync(android.content.Context context,
                                android.net.Uri mediaUri,
                                String recipientId,
                                TransportMode selectedMode,
                                String caption,
                                @androidx.annotation.Nullable com.antor.sosblue.media.MediaTransferListener listener) {
        executor.execute(() -> {
            try {
                String senderPhone = UserIdentity.getPhoneNumber(appContext);
                String normalizedRecipient = UserIdentity.normalizePhoneNumber(recipientId);
                if (senderPhone == null || normalizedRecipient == null) {
                    postFailed(listener, "", "Sender or recipient phone is null");
                    return;
                }

                Log.d(TAG, "sendMediaAsync: sender=" + senderPhone
                        + ", recipient=" + normalizedRecipient);

                // Resolve file metadata
                String fileName = com.antor.sosblue.media.MediaChunker.getFileName(context, mediaUri);
                String mimeType = context.getContentResolver().getType(mediaUri);
                if (mimeType == null) mimeType = "application/octet-stream";

                boolean isVideo = mimeType.startsWith("video/");
                boolean isImage = mimeType.startsWith("image/");
                int contentType = isVideo ? com.antor.sosblue.MessageModel.TYPE_VIDEO
                        : isImage ? com.antor.sosblue.MessageModel.TYPE_IMAGE
                        : com.antor.sosblue.MessageModel.TYPE_TEXT;

                // ── SMS media: check size BEFORE chunking to avoid waste ──
                if (selectedMode == TransportMode.SMS_FALLBACK) {
                    long fileSize = com.antor.sosblue.media.MediaChunker.getFileSize(context, mediaUri);
                    if (fileSize > SmsMediaHelper.MAX_SMS_FILE_SIZE) {
                        String errMsg = "File too large for SMS (" + fileSize
                                + " bytes). Maximum is " + SmsMediaHelper.MAX_SMS_FILE_SIZE + " bytes.";
                        Log.e(TAG, errMsg);
                        postFailed(listener, "", errMsg);
                        return;
                    }
                    if (!TransportMode.SMS_FALLBACK.isAvailable(appContext)) {
                        postFailed(listener, "", "SMS not available on this device");
                        return;
                    }
                    if (smsTransport == null) {
                        smsTransport = new SmsTransport(appContext);
                    }
                }

                // Split file into chunks (only reached if not SMS or size OK)
                com.antor.sosblue.media.MediaChunker.MediaChunk[] chunks =
                        com.antor.sosblue.media.MediaChunker.split(context, mediaUri, fileName, mimeType);

                if (chunks.length == 0) {
                    postFailed(listener, "", "Failed to read media file");
                    return;
                }

                String transferId = chunks[0].transferId;

                if (selectedMode == TransportMode.SMS_FALLBACK) {
                    // ── SMS media chunking with 10 KB limit ────────────
                    final java.util.concurrent.atomic.AtomicInteger smsSentCount =
                            new java.util.concurrent.atomic.AtomicInteger(0);
                    final java.util.concurrent.atomic.AtomicBoolean smsFailed =
                            new java.util.concurrent.atomic.AtomicBoolean(false);

                    for (int i = 0; i < chunks.length; i++) {
                        com.antor.sosblue.media.MediaChunker.MediaChunk chunk = chunks[i];

                        // Encode chunk metadata + data into binary format
                        byte[] mediaPayload = SmsMediaHelper.encodeChunk(chunk, contentType);

                        // Encrypt the complete media payload
                        byte[] encryptedPayload = MessageEncryptor.encrypt(
                                normalizedRecipient, mediaPayload);
                        byte[] nonce = MessageEncryptor.generateNonce();

                        F2PMessage f2pMsg = new F2PMessage(
                                senderPhone, normalizedRecipient, encryptedPayload,
                                System.currentTimeMillis(), nonce
                        );

                        final int sent = i + 1;
                        final String tid = transferId;
                        final int total = chunks.length;
                        smsTransport.sendEnvelope(f2pMsg,
                                new SmsTransport.OnSmsSendListener() {
                                    @Override
                                    public void onSent() {
                                        Log.d(TAG, "SMS media chunk " + sent
                                                + "/" + total + " sent");
                                        if (listener != null) {
                                            new android.os.Handler(
                                                    android.os.Looper.getMainLooper())
                                                    .post(() -> listener.onProgress(
                                                            tid, sent, total));
                                        }
                                        // Fire onComplete when all chunks have sent
                                        if (smsSentCount.incrementAndGet() == total
                                                && !smsFailed.get()) {
                                            if (listener != null) {
                                                new android.os.Handler(
                                                        android.os.Looper.getMainLooper())
                                                        .post(() -> listener.onComplete(
                                                                tid, null));
                                            }
                                        }
                                    }
                                    @Override
                                    public void onFailed(String reason) {
                                        Log.e(TAG, "SMS media chunk " + sent
                                                + "/" + total + " failed: " + reason);
                                        if (smsFailed.compareAndSet(false, true)) {
                                            postFailed(listener, tid, reason);
                                        }
                                    }
                                });
                    }

                    Log.i(TAG, "SMS media send dispatched: " + fileName
                            + " (" + chunks.length + " chunks)");
                    return;
                }

                // ── Standard F2P/Mesh: broadcast each chunk via UDP ──
                for (int i = 0; i < chunks.length; i++) {
                    com.antor.sosblue.media.MediaChunker.MediaChunk chunk = chunks[i];

                    // Encrypt the chunk data
                    byte[] encryptedChunk = MessageEncryptor.encrypt(normalizedRecipient, chunk.data);
                    byte[] nonce = MessageEncryptor.generateNonce();

                    // Build the F2P media envelope
                    F2PMessage f2pMsg = new F2PMessage(
                            senderPhone, normalizedRecipient, encryptedChunk,
                            System.currentTimeMillis(), nonce
                    );

                    // ── Build the F2P envelope Base64 ────────────────────
                    String envelopeB64 = Base64.encodeToString(f2pMsg.serialize(), Base64.NO_WRAP);

                    // ── Generate message ID for dedup + loop prevention ──
                    // Each chunk gets a fresh UUID so the dedup cache prevents
                    // re-processing our own broadcasts when received back via
                    // UDP loopback.
                    String messageId = UUID.randomUUID().toString();
                    seenMessageIds.put(messageId, System.currentTimeMillis());

                    // Build a JSON payload for UDP broadcast (same fields the
                    // UDP callback expects for media chunk reassembly).
                    String jsonPayload = buildF2pMediaPayload(
                            senderPhone, normalizedRecipient, envelopeB64,
                            messageId, transferId, chunk.chunkIndex, chunk.totalChunks,
                            chunk.fileName, chunk.mimeType, String.valueOf(contentType),
                            caption != null ? caption : "");

                    dispatchSignal("media_chunk", Map.ofEntries(
                            Map.entry("_destination", normalizedRecipient),
                            Map.entry("sender_phone", senderPhone),
                            Map.entry("recipient_phone", normalizedRecipient),
                            Map.entry("f2p_envelope", envelopeB64),
                            Map.entry("message_id", messageId),
                            Map.entry("ttl", "3"),
                            Map.entry("transfer_id", transferId),
                            Map.entry("chunk_index", String.valueOf(chunk.chunkIndex)),
                            Map.entry("total_chunks", String.valueOf(chunk.totalChunks)),
                            Map.entry("file_name", chunk.fileName),
                            Map.entry("mime_type", chunk.mimeType),
                            Map.entry("content_type", String.valueOf(contentType)),
                            Map.entry("caption", caption != null ? caption : ""),
                            Map.entry("transport", "f2p")
                    ));

                    // ── Broadcast over local UDP mesh (adds actual network send) ──
                    if (udpMeshManager != null && udpMeshManager.isRunning()) {
                        udpMeshManager.broadcast(jsonPayload);

                        // ── Also unicast directly to recipient's last-known IP ──
                        // Crucial when the recipient is on a different subnet
                        // (e.g. guest network) where broadcasts are blocked.
                        sendDirectToRecipient(jsonPayload, normalizedRecipient);
                    }

                    // Report progress
                    final int sent = i + 1;
                    final String tid = transferId;
                    final int total = chunks.length;
                    if (listener != null) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                                () -> listener.onProgress(tid, sent, total));
                    }

                    Log.d(TAG, "Media chunk " + sent + "/" + total + " sent for " + fileName
                            + " (msgId=" + messageId.substring(0, Math.min(8, messageId.length())) + "...)");
                }

                // Report completion
                if (listener != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(
                            () -> listener.onComplete(transferId, null));
                }

                Log.d(TAG, "Media send complete: " + fileName + " (" + chunks.length + " chunks)");

            } catch (Exception e) {
                Log.e(TAG, "sendMedia failed", e);
                postFailed(listener, "", e.getMessage() != null ? e.getMessage() : "Media send failed");
            }
        });
    }

    private void postFailed(@androidx.annotation.Nullable com.antor.sosblue.media.MediaTransferListener listener,
                             String transferId, String reason) {
        if (listener != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    () -> listener.onFailed(transferId, reason));
        }
    }

    // ---------------------------------------------------------------
    //  F2P Payload Helper
    // ---------------------------------------------------------------

    /**
     * Builds a JSON payload string suitable for UDP broadcast.
     * Contains all the fields needed by the receiving device to
     * decrypt and deliver the message.
     */
    private static String buildF2pPayload(String senderPhone, String recipientPhone,
                                           F2PMessage f2pMsg, String messageId) {
        String envelopeB64 = Base64.encodeToString(f2pMsg.serialize(), Base64.NO_WRAP);
        return "{"
                + "\"sender_phone\":\"" + senderPhone + "\""
                + ",\"recipient_phone\":\"" + recipientPhone + "\""
                + ",\"f2p_envelope\":\"" + envelopeB64 + "\""
                + ",\"transport\":\"f2p\""
                + ",\"ttl\":\"3\""
                + ",\"message_id\":\"" + messageId + "\""
                + "}";
    }

    /**
     * Builds a JSON payload string for a media chunk UDP broadcast.
     * Contains all the fields needed by the receiving device's UDP
     * callback to reconstruct the signal and reassemble the file.
     */
    private static String buildF2pMediaPayload(String senderPhone, String recipientPhone,
                                                String envelopeB64, String messageId,
                                                String transferId, int chunkIndex, int totalChunks,
                                                String fileName, String mimeType,
                                                String contentType, String caption) {
        return "{"
                + "\"sender_phone\":\"" + escapeJson(senderPhone) + "\""
                + ",\"recipient_phone\":\"" + escapeJson(recipientPhone) + "\""
                + ",\"f2p_envelope\":\"" + envelopeB64 + "\""
                + ",\"message_id\":\"" + messageId + "\""
                + ",\"transfer_id\":\"" + transferId + "\""
                + ",\"chunk_index\":\"" + chunkIndex + "\""
                + ",\"total_chunks\":\"" + totalChunks + "\""
                + ",\"file_name\":\"" + escapeJson(fileName) + "\""
                + ",\"mime_type\":\"" + escapeJson(mimeType) + "\""
                + ",\"content_type\":\"" + contentType + "\""
                + ",\"caption\":\"" + escapeJson(caption) + "\""
                + ",\"transport\":\"f2p\""
                + ",\"ttl\":\"3\""
                + "}";
    }

    /**
     * Escapes a string value for safe inclusion in a JSON payload.
     * Replaces backslashes, quotes, and control characters.
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ---------------------------------------------------------------
    //  Peer discovery listener notification
    // ---------------------------------------------------------------

    /** Notifies all registered {@link PeerDiscoveryListener}s of a discovered peer. */
    private void notifyPeerDiscovered(String nodeId, @androidx.annotation.Nullable String username,
                                       @androidx.annotation.Nullable String phone,
                                       String ipAddress, int port) {
        for (PeerDiscoveryListener l : peerDiscoveryListeners) {
            try {
                l.onPeerDiscovered(nodeId, username, phone, ipAddress, port);
            } catch (Exception e) {
                Log.e(TAG, "PeerDiscoveryListener threw", e);
            }
        }
    }

    /** Notifies all registered {@link PeerDiscoveryListener}s of a lost peer. */
    private void notifyPeerLost(String nodeId) {
        for (PeerDiscoveryListener l : peerDiscoveryListeners) {
            try {
                l.onPeerLost(nodeId);
            } catch (Exception e) {
                Log.e(TAG, "PeerDiscoveryListener threw", e);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Direct unicast to recipient (bypass AP isolation)
    // ---------------------------------------------------------------

    /**
     * Attempts a unicast directly to the recipient's last-known IP:port,
     * bypassing AP isolation that blocks subnet broadcasts from guest
     * networks back to the main LAN.
     * <p>
     * The recipient's IP:port is already tracked by
     * {@link UdpMeshManager#updatePeerEndpoint} whenever a UDP datagram
     * arrives from them (heartbeat or message).
     * Falls back silently if no endpoint is known.</p>
     *
     * @param jsonPayload the complete UDP JSON payload to send
     * @param recipientId phone number (or node ID) of the target peer
     */
    private void sendDirectToRecipient(String jsonPayload, String recipientId) {
        if (udpMeshManager == null || !udpMeshManager.isRunning()) return;
        if (recipientId == null) return;

        try {
            String normalizedKey = UserIdentity.normalizePhoneNumber(recipientId);
            if (normalizedKey == null) return;

            UdpMeshManager.PeerEndpoint ep = udpMeshManager.getPeerEndpoint(normalizedKey);
            if (ep != null) {
                udpMeshManager.sendDirect(jsonPayload, ep.ipAddress, ep.port);
                Log.d(TAG, "Direct unicast to " + ep.ipAddress + ":" + ep.port
                        + " for " + normalizedKey);
            } else {
                Log.v(TAG, "No known peer endpoint for " + normalizedKey
                        + " — broadcast only");
            }
        } catch (Exception e) {
            Log.w(TAG, "sendDirectToRecipient failed for " + recipientId, e);
        }
    }

    // ---------------------------------------------------------------
    //  ACK / Direct messaging
    // ---------------------------------------------------------------

    /**
     * Sends a lightweight ACK (acknowledgment) packet directly back to
     * the sender's dynamic IP:port so they know the message was received.
     * <p>
     * This uses {@link UdpMeshManager#sendDirect} to address the sender
     * directly rather than relying on a subnet broadcast — critical when
     * the sender is on a different subnet or after a network switch.</p>
     *
     * @param messageId    the message being acknowledged
     * @param senderPhone  the sender's phone number (used as node ID)
     * @param sourceIp     the sender's IP address from the received datagram
     * @param sourcePort   the sender's UDP port from the received datagram
     */
    private void sendMessageAck(@androidx.annotation.Nullable String messageId,
                                 @androidx.annotation.Nullable String senderPhone,
                                 String sourceIp, int sourcePort) {
        if (messageId == null || sourceIp == null) return;

        try {
            String localPhone = UserIdentity.getPhoneNumber(appContext);
            String ackPayload = "{"
                    + "\"type\":\"ack\""
                    + ",\"message_id\":\"" + messageId + "\""
                    + ",\"recipient_phone\":\"" + (senderPhone != null ? senderPhone : "") + "\""
                    + ",\"acknowledger_phone\":\"" + (localPhone != null ? localPhone : "") + "\""
                    + ",\"timestamp\":" + System.currentTimeMillis()
                    + "}";

            if (udpMeshManager != null) {
                udpMeshManager.sendDirect(ackPayload, sourceIp, sourcePort);
            }
            Log.d(TAG, "ACK sent to " + sourceIp + ":" + sourcePort
                    + " for message " + messageId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send ACK to " + sourceIp, e);
        }
    }

    // ---------------------------------------------------------------
    //  Accessors for sub-managers
    // ---------------------------------------------------------------

    /**
     * Returns the {@link NetworkConnectivityManager} that monitors Wi-Fi
     * network changes. Callers can register additional listeners.
     */
    public NetworkConnectivityManager getConnectivityManager() {
        return connectivityManager;
    }

    /**
     * Returns the {@link WifiDirectManager} for Wi-Fi Direct fallback
     * discovery. Callers can register additional listeners.
     */
    public WifiDirectManager getWifiDirectManager() {
        return wifiDirectManager;
    }

    /**
     * Returns the current local IP address, or null if not available.
     */
    @androidx.annotation.Nullable
    public String getCurrentLocalIp() {
        return connectivityManager != null
                ? connectivityManager.getCurrentLocalIp() : null;
    }

    // ---------------------------------------------------------------
    //  F2P Identity helpers
    // ---------------------------------------------------------------

    /**
     * Returns the local user's phone number (unique peer identifier).
     */
    @androidx.annotation.Nullable
    public String getLocalPhoneNumber() {
        return UserIdentity.getPhoneNumber(appContext);
    }

    /**
     * Returns the local user's display name.
     */
    @androidx.annotation.Nullable
    public String getLocalUsername() {
        return UserIdentity.getUsername(appContext);
    }

    // ---------------------------------------------------------------
    //  Listeners
    // ---------------------------------------------------------------

    public void registerListener(EngineCallback callback) {
        engine.registerListener(callback);
    }

    public void unregisterListener(EngineCallback callback) {
        engine.unregisterListener(callback);
    }

    // ---------------------------------------------------------------
    //  State queries
    // ---------------------------------------------------------------

    public EngineState getEngineState()       { return engine.getState(); }
    public boolean isRouting()                { return engine.isRouting(); }
    public boolean isMeshConnected()          { return engine.isConnected(); }
    public String getLocalNodeId()            { return engine.getLocalNodeId(); }
    public void setLocalNodeId(String id)     { engine.setLocalNodeId(id); }

    // ---------------------------------------------------------------
    //  Telemetry
    // ---------------------------------------------------------------

    public MeshHealthSnapshot getMeshHealth() {
        return engine.getHealthSnapshot();
    }

    public int getActiveNodeCount() {
        return engine.getHealthSnapshot().getActiveNodeCount();
    }

    public int getActiveRouteCount() {
        return engine.getRoutingTable().getPathRouter().getActiveRouteCount();
    }

    public int getKnownPeerCount() {
        return engine.getPeerDiscovery().getKnownPeers().size();
    }

    public int getPendingAckCount() {
        return engine.getRoutingTable().getPathRouter().getPendingAckCount();
    }

    public double getAverageLatencyMs() {
        return engine.getHealthSnapshot().getAverageLatencyMs();
    }

    public int getActiveSessionCount() {
        return engine.getSecurityHandler().getActiveSessionCount();
    }

    /** Returns the number of packets queued in the offline store. */
    public int getQueuedOfflineCount() {
        return engine.getFibreStore().queuedPacketCount();
    }

    /** Changes the log level at runtime (no restart needed). */
    public void setLogLevel(LogLevel level) {
        engine.getFibreLogger().setLevel(level);
    }

    /** Returns the current log level. */
    public LogLevel getLogLevel() {
        return engine.getFibreLogger().getLevel();
    }
}
