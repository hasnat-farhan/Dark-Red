package com.antor.sosblue.bridge;

import android.util.Log;

import com.antor.f2p.engine.api.EngineCallback;
import com.antor.f2p.engine.api.EngineConfig;
import com.antor.f2p.engine.api.EngineState;
import com.antor.f2p.engine.api.FibrePacket;
import com.antor.f2p.engine.api.FibreSignal;
import com.antor.f2p.engine.api.LogLevel;
import com.antor.f2p.engine.api.MeshHealthSnapshot;
import com.antor.f2p.engine.api.WanderingFibreEngine;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** Error-boundary status code: last non-fatal error or 0 if healthy. */
    private volatile int lastStatusCode;
    private volatile String lastStatusMessage;

    public F2PBridge() {
        this.engine = new WanderingFibreEngine();
        this.started = new AtomicBoolean(false);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "f2p-engine-init");
            t.setDaemon(true);
            return t;
        });
        this.lastStatusCode = 0;
        this.lastStatusMessage = "OK";
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
     *
     * @param messageText  the text to send
     * @param recipientId  destination node ID
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
                dispatchSignal("chat_message", Map.of(
                        "recipient", recipientId,
                        "text", messageText,
                        "transport", "f2p"
                ));
                Log.d(TAG, "sendMessage → F2P_SERVERLESS to " + recipientId);
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
     * Sends a message on the background executor and invokes {@code onComplete}
     * on the main thread when the dispatch finishes.
     */
    public void sendMessageAsync(String messageText, String recipientId,
                                  TransportMode selectedMode,
                                  @androidx.annotation.Nullable Runnable onComplete) {
        executor.execute(() -> {
            sendMessage(messageText, recipientId, selectedMode);
            if (onComplete != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(onComplete);
            }
        });
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
