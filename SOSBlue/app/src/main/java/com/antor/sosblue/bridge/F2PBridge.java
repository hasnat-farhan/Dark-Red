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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /** Application context — avoids hidden API ActivityThread.currentApplication(). */
    private final Context appContext;

    /** UDP mesh manager for local Wi-Fi broadcast / receive. */
    private UdpMeshManager udpMeshManager;

    /** Packet counter for logging. */
    private final AtomicInteger packetSequence;

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

        // ── Start UDP mesh manager (for receiving) ────────────────────
        startUdpMesh();

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
        // ── Stop UDP mesh first ─────────────────────────────────────
        stopUdpMesh();
        try {
            engine.shutdown();
            executor.shutdownNow();
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

            PeerDiscoveryHandler handler = new PeerDiscoveryHandler(
                    intervalMs,
                    discoveryPort,
                    nodeId,
                    localPhone != null ? localPhone : "",
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

                        if (peerNodeId != null) {
                            String endpoint = sourceAddress + ":" + sourcePort;
                            Log.d(TAG, "[" + seq + "] Heartbeat from node="
                                    + peerNodeId + ", phone=" + peerPhone
                                    + " @ " + endpoint);

                            // Ignore our own heartbeats
                            String localPhone = UserIdentity.getPhoneNumber(appContext);
                            String normalizedLocal = UserIdentity.normalizePhoneNumber(localPhone);
                            String normalizedPeer = UserIdentity.normalizePhoneNumber(peerPhone);
                            if (normalizedLocal != null && normalizedLocal.equals(normalizedPeer)) {
                                Log.v(TAG, "[" + seq + "] Ignoring our own heartbeat");
                                return;
                            }

                            // Register peer via the engine's handler
                            engine.getPeerDiscoveryHandler().onHeartbeatReceived(
                                    peerNodeId, endpoint);
                            Log.i(TAG, "[" + seq + "] Peer discovered: " + peerNodeId);
                        } else {
                            Log.w(TAG, "[" + seq + "] Heartbeat missing node_id");
                        }
                        return;
                    }

                    // ── Parse received JSON fields and rebuild proper signal ──
                    String recipientPhone = com.antor.sosblue.identity.JsonPayloadHelper
                            .extractField(payloadJson, "recipient_phone");
                    String senderPhone = com.antor.sosblue.identity.JsonPayloadHelper
                            .extractField(payloadJson, "sender_phone");
                    String envelopeB64 = com.antor.sosblue.identity.JsonPayloadHelper
                            .extractField(payloadJson, "f2p_envelope");
                    String transport = com.antor.sosblue.identity.JsonPayloadHelper
                            .extractField(payloadJson, "transport");

                    // ── Ignore our own broadcasts to prevent self-loop ──
                    // Since UDP sockets receive their own broadcasts, we must
                    // filter out messages where the sender matches our phone.
                    if (senderPhone != null) {
                        String localPhone = UserIdentity.getPhoneNumber(appContext);
                        String normalizedLocal = UserIdentity.normalizePhoneNumber(localPhone);
                        String normalizedSender = UserIdentity.normalizePhoneNumber(senderPhone);
                        if (normalizedLocal != null && normalizedLocal.equals(normalizedSender)) {
                            Log.v(TAG, "[" + seq + "] Ignoring our own broadcast");
                            return;
                        }
                    }

                    if (recipientPhone != null && envelopeB64 != null) {
                        HashMap<String, Object> fields = new HashMap<>();
                        fields.put("_destination", recipientPhone);
                        fields.put("sender_phone", senderPhone != null ? senderPhone : "");
                        fields.put("recipient_phone", recipientPhone);
                        fields.put("f2p_envelope", envelopeB64);
                        fields.put("transport", transport != null ? transport : "f2p");

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
                    Log.d(TAG, "Message also broadcast via UDP mesh");
                }

                Log.d(TAG, "sendMessage → F2P_SERVERLESS (encrypted) to " + recipientPhone);
                return true;
            }
            default:
                return false;
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

                // Split file into chunks
                com.antor.sosblue.media.MediaChunker.MediaChunk[] chunks =
                        com.antor.sosblue.media.MediaChunker.split(context, mediaUri, fileName, mimeType);

                if (chunks.length == 0) {
                    postFailed(listener, "", "Failed to read media file");
                    return;
                }

                String transferId = chunks[0].transferId;

                // Dispatch each chunk as an encrypted F2P signal
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

                    dispatchSignal("media_chunk", Map.ofEntries(
                            Map.entry("_destination", normalizedRecipient),
                            Map.entry("sender_phone", senderPhone),
                            Map.entry("recipient_phone", normalizedRecipient),
                            Map.entry("f2p_envelope", Base64.encodeToString(f2pMsg.serialize(), Base64.NO_WRAP)),
                            Map.entry("transfer_id", transferId),
                            Map.entry("chunk_index", String.valueOf(chunk.chunkIndex)),
                            Map.entry("total_chunks", String.valueOf(chunk.totalChunks)),
                            Map.entry("file_name", chunk.fileName),
                            Map.entry("mime_type", chunk.mimeType),
                            Map.entry("content_type", String.valueOf(contentType)),
                            Map.entry("caption", caption != null ? caption : ""),
                            Map.entry("transport", "f2p")
                    ));

                    // Report progress
                    final int sent = i + 1;
                    final String tid = transferId;
                    final int total = chunks.length;
                    if (listener != null) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                                () -> listener.onProgress(tid, sent, total));
                    }

                    Log.d(TAG, "Media chunk " + sent + "/" + total + " sent for " + fileName);
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
