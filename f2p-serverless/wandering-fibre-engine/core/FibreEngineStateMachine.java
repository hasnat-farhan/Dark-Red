package com.antor.f2p.engine.core;

import com.antor.f2p.engine.api.EngineState;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Event-driven state machine that governs the Wandering Fibre Engine's
 * mesh lifecycle.
 * <p>
 * Unlike the pure validation-only {@link StateMachine}, this class
 * <strong>drives</strong> transitions by providing named trigger methods
 * ({@link #discoverPeers()}, {@link #meshConnected()}, {@link #startRouting()},
 * etc.) and accepts a listener for state change notifications.
 * </p>
 *
 * <h3>Transition Diagram</h3>
 * <pre>
 * UNINITIALIZED ──► DISCOVERING_PEERS ──► CONNECTED_MESH ──► ROUTING
 *                      │                      │                 │
 *                      ├──► ERROR              ├──► ERROR       ├──► ERROR
 *                      │                      │                 │
 *                      └──► DISCONNECTED       └──► DISCONNECTED └──► DISCONNECTED
 *                                                                │
 *                      DISCONNECTED ──► DISCOVERING_PEERS        │
 *                      ERROR ──► DISCONNECTED (recovery)         │
 *                      ERROR ──► UNINITIALIZED (full reset)      │
 * </pre>
 */
public class FibreEngineStateMachine {

    private static final Logger LOG = Logger.getLogger(FibreEngineStateMachine.class.getName());

    private final StateMachine stateMachine;
    private final ReentrantLock lock;
    private volatile EngineState currentState;
    private volatile Consumer<EngineState> onStateChangeListener;

    /** Builds the state machine with all allowed transitions. */
    public FibreEngineStateMachine() {
        this.stateMachine = new StateMachine();
        this.lock = new ReentrantLock();
        this.currentState = EngineState.UNINITIALIZED;
    }

    // ---------------------------------------------------------------
    //  Configuration
    // ---------------------------------------------------------------

    /**
     * Registers a listener that fires on every state change.
     *
     * @param listener receives the new state after each transition
     */
    public void setOnStateChangeListener(Consumer<EngineState> listener) {
        this.onStateChangeListener = listener;
    }

    // ---------------------------------------------------------------
    //  Named triggers — the primary API
    // ---------------------------------------------------------------

    /** Begins peer discovery (UNINITIALIZED → DISCOVERING_PEERS). */
    public void discoverPeers() {
        transition(EngineState.DISCOVERING_PEERS);
    }

    /** Fired when the mesh is established (DISCOVERING_PEERS → CONNECTED_MESH). */
    public void meshConnected() {
        transition(EngineState.CONNECTED_MESH);
    }

    /** Fired when routing is active (CONNECTED_MESH → ROUTING). */
    public void startRouting() {
        transition(EngineState.ROUTING);
    }

    /** Fired when routing stops or fails (ROUTING → CONNECTED_MESH). */
    public void stopRouting() {
        transition(EngineState.CONNECTED_MESH);
    }

    /** Fired when the mesh is lost (ROUTING/CONNECTED_MESH → DISCONNECTED). */
    public void disconnect() {
        transition(EngineState.DISCONNECTED);
    }

    /** Attempts reconnection (DISCONNECTED → DISCOVERING_PEERS). */
    public void reconnect() {
        transition(EngineState.DISCOVERING_PEERS);
    }

    /** Fired on a fatal error (any non-terminal → ERROR). */
    public void fail() {
        EngineState current = currentState;
        if (current != EngineState.ERROR && current != EngineState.UNINITIALIZED) {
            transition(EngineState.ERROR);
        }
    }

    /** Full reset from error state (ERROR → UNINITIALIZED). */
    public void reset() {
        transition(EngineState.UNINITIALIZED);
    }

    // ---------------------------------------------------------------
    //  Queries
    // ---------------------------------------------------------------

    /** Returns the current state. */
    public EngineState getCurrentState() {
        return currentState;
    }

    /** Returns true if the engine is in a state where packets can be routed. */
    public boolean isRouting() {
        return currentState == EngineState.ROUTING;
    }

    /** Returns true if the engine is connected to a mesh. */
    public boolean isConnected() {
        return currentState == EngineState.CONNECTED_MESH
                || currentState == EngineState.ROUTING;
    }

    /** Returns true if the engine can accept a trigger. */
    public boolean isOperational() {
        return currentState != EngineState.ERROR
                && currentState != EngineState.UNINITIALIZED;
    }

    // ---------------------------------------------------------------
    //  Internal
    // ---------------------------------------------------------------

    private void transition(EngineState target) {
        lock.lock();
        try {
            EngineState previous = currentState;
            if (previous == target) {
                return; // no-op
            }
            stateMachine.transition(previous, target);
            currentState = target;
            LOG.fine(() -> "FibreEngine state: " + previous + " -> " + target);
            Consumer<EngineState> listener = onStateChangeListener;
            if (listener != null) {
                try {
                    listener.accept(target);
                } catch (Exception e) {
                    LOG.warning("State change listener threw: " + e.getMessage());
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
