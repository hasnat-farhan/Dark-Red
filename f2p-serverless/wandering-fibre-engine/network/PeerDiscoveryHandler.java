package com.antor.f2p.engine.network;

import com.antor.f2p.engine.core.FibreEngineStateMachine;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Lightweight mock peer discovery handler.
 * <p>
 * Simulates heartbeat-based peer detection: after a configurable number of
 * heartbeat cycles, enough peers are "detected" and the engine transitions
 * from {@code DISCOVERING_PEERS} → {@code CONNECTED_MESH}.
 * </p>
 *
 * <p>
 * This is a <strong>mock</strong> implementation for scaffolding and testing.
 * Replace with a real UDP/mDNS/gossip-based discovery for production.
 * </p>
 */
public class PeerDiscoveryHandler {

    private static final Logger LOG = Logger.getLogger(PeerDiscoveryHandler.class.getName());

    private static final int DEFAULT_HEARTBEAT_INTERVAL_MS = 500;
    private static final int DEFAULT_PEERS_REQUIRED = 3;
    private static final int DEFAULT_MAX_HEARTBEAT_CYCLES = 5;

    private final int heartbeatIntervalMs;
    private final int peersRequired;
    private final int maxHeartbeatCycles;

    private final AtomicBoolean running;
    private final AtomicInteger heartbeatCycle;
    private final Random random;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private FibreEngineStateMachine stateMachine;

    /** Creates a handler with default parameters. */
    public PeerDiscoveryHandler() {
        this(DEFAULT_HEARTBEAT_INTERVAL_MS, DEFAULT_PEERS_REQUIRED, DEFAULT_MAX_HEARTBEAT_CYCLES);
    }

    /**
     * Creates a handler with custom parameters.
     *
     * @param heartbeatIntervalMs interval between heartbeat broadcasts (ms)
     * @param peersRequired       number of peers needed to consider the mesh formed
     * @param maxHeartbeatCycles  max heartbeat rounds before declaring failure
     */
    public PeerDiscoveryHandler(int heartbeatIntervalMs,
                                int peersRequired,
                                int maxHeartbeatCycles) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.peersRequired = peersRequired;
        this.maxHeartbeatCycles = maxHeartbeatCycles;
        this.running = new AtomicBoolean(false);
        this.heartbeatCycle = new AtomicInteger(0);
        this.random = new Random();
    }

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    /**
     * Binds this handler to a state machine and begins mock discovery.
     *
     * @param fsm the engine's state machine (must be in DISCOVERING_PEERS)
     */
    public void start(FibreEngineStateMachine fsm) {
        if (running.getAndSet(true)) {
            return;
        }
        this.stateMachine = fsm;
        this.heartbeatCycle.set(0);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "peer-discovery-heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatFuture = scheduler.scheduleAtFixedRate(
                this::heartbeatTick,
                0,
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS
        );

        LOG.info("PeerDiscoveryHandler started (interval=" + heartbeatIntervalMs
                + "ms, peersRequired=" + peersRequired
                + ", maxCycles=" + maxHeartbeatCycles + ")");
    }

    /** Stops the heartbeat scheduler. */
    public void stop() {
        running.set(false);
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        LOG.info("PeerDiscoveryHandler stopped");
    }

    // ---------------------------------------------------------------
    //  Mock heartbeat
    // ---------------------------------------------------------------

    private void heartbeatTick() {
        if (!running.get() || stateMachine == null) {
            return;
        }

        int cycle = heartbeatCycle.incrementAndGet();
        LOG.fine("Heartbeat cycle " + cycle + "/" + maxHeartbeatCycles);

        // Simulate hearing from 1..peersRequired peers each cycle (guarantees ≥1)
        int peersHeard = 1 + random.nextInt(peersRequired);
        LOG.fine("Heard " + peersHeard + " peer(s) this cycle");

        // After enough cycles, transition to CONNECTED_MESH
        if (cycle >= maxHeartbeatCycles) {
            if (peersHeard >= peersRequired / 2) {
                LOG.info("Sufficient peers detected — transitioning to CONNECTED_MESH");
                stateMachine.meshConnected();
                stop(); // discovery complete
            } else {
                LOG.warning("Insufficient peers after " + maxHeartbeatCycles
                        + " cycles — transitioning to ERROR");
                stateMachine.fail();
                stop();
            }
        }
    }
}
