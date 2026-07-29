package com.antor.f2p.engine.api;

import com.antor.f2p.engine.core.EngineLoop;
import com.antor.f2p.engine.core.FibreEngineStateMachine;
import com.antor.f2p.engine.core.FibreLogger;
import com.antor.f2p.engine.core.FibreSecurityHandler;
import com.antor.f2p.engine.core.FibreStore;
import com.antor.f2p.engine.core.StateMachine;
import com.antor.f2p.engine.network.PeerDiscovery;
import com.antor.f2p.engine.network.PeerDiscoveryHandler;
import com.antor.f2p.engine.network.RoutingTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Public-facing entry point for the Wandering Fibre Engine.
 * <p>
 * Runs its own internal execution loop on a dedicated daemon thread so it
 * never blocks the caller (SOSBlue).  SOSBlue interacts with the engine
 * exclusively through the {@code api} package.
 * </p>
 */
public class WanderingFibreEngine {

    private static final Logger LOG = Logger.getLogger(WanderingFibreEngine.class.getName());

    static final String DEFAULT_NODE_ID = "sosblue-node";

    private final AtomicReference<EngineState> state;
    private final CopyOnWriteArrayList<EngineCallback> listeners;
    private final StateMachine stateMachine;
    private final FibreEngineStateMachine fibreEngineStateMachine;
    private final EngineLoop engineLoop;
    private final PeerDiscovery peerDiscovery;
    private final PeerDiscoveryHandler peerDiscoveryHandler;
    private RoutingTable routingTable;
    private final FibreSecurityHandler securityHandler;
    private final FibreStore fibreStore;
    private final FibreLogger fibreLogger;

    private volatile String localNodeId;
    private final AtomicBoolean paused;

    public WanderingFibreEngine() {
        this.state = new AtomicReference<>(EngineState.UNINITIALIZED);
        this.listeners = new CopyOnWriteArrayList<>();
        this.stateMachine = new StateMachine();
        this.fibreEngineStateMachine = new FibreEngineStateMachine();
        this.peerDiscovery = new PeerDiscovery();
        this.peerDiscoveryHandler = new PeerDiscoveryHandler();
        this.localNodeId = DEFAULT_NODE_ID;
        this.routingTable = null; // initialised inside initialize() after configure()
        this.securityHandler = new FibreSecurityHandler();
        this.fibreStore = new FibreStore();
        this.fibreLogger = FibreLogger.get();
        this.engineLoop = new EngineLoop(this);
        this.paused = new AtomicBoolean(false);
    }

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    /**
     * Applies an {@link EngineConfig} before calling {@link #initialize()}.
     * Does nothing if the engine is already initialised.
     */
    public synchronized void configure(EngineConfig config) {
        if (state.get() != EngineState.UNINITIALIZED) return;
        localNodeId = config.getNodeId();
        fibreLogger.setLevel(config.getLogLevel());
    }

    public synchronized void initialize() {
        if (state.get() != EngineState.UNINITIALIZED) {
            throw new IllegalStateException(
                    "Engine already initialised (current state=" + state.get() + ")");
        }

        // Defer RoutingTable creation to here so configure() can set localNodeId first
        this.routingTable = new RoutingTable(peerDiscovery, localNodeId);

        // Start the async logger
        fibreLogger.start();
        fibreLogger.info("Wandering Fibre Engine initialising (node=" + localNodeId + ")");

        // Load persisted state from disk
        try {
            fibreStore.load();
        } catch (Exception e) {
            fibreLogger.warn("Failed to load FibreStore", e);
        }

        // Initialise crypto subsystem
        try {
            securityHandler.initialize();
        } catch (Exception e) {
            notifyEngineError(100, "Crypto init failed", e);
        }

        // Register the single state change listener
        fibreEngineStateMachine.setOnStateChangeListener(newState -> {
            EngineState previous = state.getAndSet(newState);
            if (previous != newState) {
                notifyStateChanged(newState);
            }
            if (newState == EngineState.CONNECTED_MESH) {
                fibreLogger.info("Mesh established — flushing queued packets");
                fibreEngineStateMachine.startRouting();
                // Replay any packets queued during offline
                fibreStore.flushQueuedPackets(pkt -> engineLoop.enqueuePacket(pkt));
            }
            if (newState == EngineState.DISCONNECTED) {
                fibreLogger.warn("Mesh disconnected — will queue packets for later replay");
            }
        });

        fibreEngineStateMachine.discoverPeers();
        peerDiscovery.initialize();

        routingTable.onTopologyChange(() ->
                fibreLogger.debug("Topology change detected"));

        peerDiscoveryHandler.start(fibreEngineStateMachine);
        engineLoop.start();

        fibreLogger.info("Wandering Fibre Engine initialised (node=" + localNodeId + ")");
    }

    public synchronized void shutdown() {
        EngineState current = state.get();
        if (current == EngineState.UNINITIALIZED) return;

        peerDiscoveryHandler.stop();
        engineLoop.stop();
        routingTable.clear();
        securityHandler.clearAllSessions();
        peerDiscovery.shutdown();
        stateMachine.reset();

        // Persist caches for next startup
        try {
            fibreStore.cachePeerMetrics(routingTable.getAllLinkMetrics());
            fibreStore.cacheRoutes(routingTable.getAllRoutes());
            fibreStore.flushAndClose();
        } catch (Exception e) {
            fibreLogger.warn("Failed to flush FibreStore during shutdown", e);
        }

        fibreLogger.info("Wandering Fibre Engine shut down");
        fibreLogger.stop();
    }

    // ---------------------------------------------------------------
    //  Pause / Resume
    // ---------------------------------------------------------------

    /** Pauses packet routing. Queued signals are still accepted but not processed. */
    public void pauseRouting() {
        paused.set(true);
        fibreLogger.info("Routing paused");
    }

    /** Resumes packet routing and flushes any packets queued during pause. */
    public void resumeRouting() {
        paused.set(false);
        int count = fibreStore.queuedPacketCount();
        fibreStore.flushQueuedPackets(pkt -> engineLoop.enqueuePacket(pkt));
        fibreLogger.info("Routing resumed — flushed " + count + " queued packets");
    }

    public boolean isPaused() { return paused.get(); }

    // ---------------------------------------------------------------
    //  Signal dispatch
    // ---------------------------------------------------------------

    public void dispatchSignal(FibreSignal signal) {
        EngineState current = state.get();
        if (paused.get()) {
            // Queue the serialised type + id for later replay
            byte[] payload = signal.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            fibreStore.queuePacket(new FibrePacket(localNodeId, "broadcast",
                    signal.getType(), payload));
            return;
        }
        if (current != EngineState.ROUTING && current != EngineState.CONNECTED_MESH) {
            byte[] payload = signal.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            fibreStore.queuePacket(new FibrePacket(localNodeId, "broadcast",
                    signal.getType(), payload));
            return;
        }
        engineLoop.enqueue(signal);
    }

    public void dispatchSignal(String type, Map<String, Object> payload) {
        dispatchSignal(new FibreSignal(type, payload));
    }

    // ---------------------------------------------------------------
    //  Listeners
    // ---------------------------------------------------------------

    public void registerListener(EngineCallback callback) {
        listeners.addIfAbsent(callback);
    }

    public void unregisterListener(EngineCallback callback) {
        listeners.remove(callback);
    }

    // ---------------------------------------------------------------
    //  State queries
    // ---------------------------------------------------------------

    public EngineState getState()                    { return state.get(); }
    public boolean isRouting()                       { return fibreEngineStateMachine.isRouting(); }
    public boolean isConnected()                     { return fibreEngineStateMachine.isConnected(); }

    public FibreEngineStateMachine getFibreEngineStateMachine() {
        return fibreEngineStateMachine;
    }

    // ---------------------------------------------------------------
    //  Telemetry
    // ---------------------------------------------------------------

    public MeshHealthSnapshot getHealthSnapshot() {
        List<String> knownPeers = new ArrayList<>(peerDiscovery.getKnownPeers());
        return new MeshHealthSnapshot(
                routingTable.getPathRouter().getAlivePeers().size(),
                routingTable.getAverageLatencyMs(),
                routingTable.getAverageSignalStrength(),
                routingTable.getPathRouter().getActiveRouteCount(),
                routingTable.getOutboundCount(),
                routingTable.getInboundCount(),
                routingTable.getForwardedCount(),
                routingTable.getDroppedCount(),
                state.get(),
                knownPeers
        );
    }

    /** Returns a human-readable network diagnostics string. */
    public String getNetworkDiagnostics() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("=== Fibre Network Diagnostics ===\n");
        sb.append("State: ").append(state.get()).append('\n');
        sb.append("Node: ").append(localNodeId).append('\n');
        sb.append("Peers: ").append(peerDiscovery.getKnownPeers().size())
                .append(" known, ")
                .append(routingTable.getPathRouter().getAlivePeers().size())
                .append(" alive\n");
        sb.append("Routes: ").append(routingTable.getPathRouter().getActiveRouteCount())
                .append(" active\n");
        sb.append("Packets: ").append(routingTable.getOutboundCount()).append(" out, ")
                .append(routingTable.getInboundCount()).append(" in, ")
                .append(routingTable.getForwardedCount()).append(" fwd, ")
                .append(routingTable.getDroppedCount()).append(" dropped\n");
        sb.append("Pending ACK: ").append(routingTable.getPathRouter().getPendingAckCount())
                .append('\n');
        sb.append("Queued offline: ").append(fibreStore.queuedPacketCount()).append('\n');
        sb.append("Sessions: ").append(securityHandler.getActiveSessionCount()).append('\n');
        sb.append("Log level: ").append(fibreLogger.getLevel()).append('\n');
        sb.append("Paused: ").append(paused.get()).append('\n');
        sb.append("==================================");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    //  Subsystem access
    // ---------------------------------------------------------------

    public RoutingTable getRoutingTable()           { return routingTable; }
    public FibreSecurityHandler getSecurityHandler() { return securityHandler; }
    public PeerDiscovery getPeerDiscovery()         { return peerDiscovery; }
    public FibreStore getFibreStore()               { return fibreStore; }
    public FibreLogger getFibreLogger()              { return fibreLogger; }
    public String getLocalNodeId()                  { return localNodeId; }
    public void setLocalNodeId(String id)           { this.localNodeId = id; }
    StateMachine getStateMachine()                  { return stateMachine; }

    // ---------------------------------------------------------------
    //  Notification helpers
    // ---------------------------------------------------------------

    public void notifySignal(FibreSignal signal) {
        for (EngineCallback l : listeners) {
            try { l.onSignal(signal); }
            catch (Exception e) {
                fibreLogger.warn("Listener threw in onSignal", e);
                notifyEngineError(201, "onSignal listener threw", e);
            }
        }
    }

    public void notifyPacket(FibrePacket packet) {
        for (EngineCallback l : listeners) {
            try { l.onPacketReceived(packet); }
            catch (Exception e) {
                fibreLogger.warn("Listener threw in onPacketReceived", e);
                notifyEngineError(202, "onPacketReceived listener threw", e);
            }
        }
    }

    public void notifyStateChanged(EngineState newState) {
        String name = newState.name();
        for (EngineCallback l : listeners) {
            try {
                l.onStateChanged(name);
            } catch (Exception e) {
                fibreLogger.warn("Listener threw in onStateChanged", e);
                notifyEngineError(203, "onStateChanged listener threw", e);
            }
        }
    }

    public void notifyError(Throwable throwable) {
        for (EngineCallback l : listeners) {
            try { l.onError(throwable); }
            catch (Exception e) {
                fibreLogger.warn("Listener threw in onError", e);
            }
        }
    }

    /** Fires the error-boundary callback with a status code. */
    public void notifyEngineError(int statusCode, String message, Throwable cause) {
        fibreLogger.error("Engine error [" + statusCode + "]: " + message, cause);
        for (EngineCallback l : listeners) {
            try { l.onEngineError(statusCode, message, cause); }
            catch (Exception e) {
                LOG.log(Level.WARNING, "onEngineError listener threw", e);
            }
        }
    }

    /** Fires the diagnostics callback. */
    public void notifyDiagnostics() {
        String diag = getNetworkDiagnostics();
        for (EngineCallback l : listeners) {
            try { l.onDiagnostics(diag); }
            catch (Exception e) {
                LOG.log(Level.WARNING, "onDiagnostics listener threw", e);
            }
        }
    }
}
