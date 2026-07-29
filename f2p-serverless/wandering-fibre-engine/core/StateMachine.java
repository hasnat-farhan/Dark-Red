package com.antor.f2p.engine.core;

import com.antor.f2p.engine.api.EngineState;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Pure validation engine for the Wandering Fibre Engine's mesh state machine.
 * Defines every legal transition and throws on invalid ones.
 * <p>
 * This class contains <strong>only</strong> transition rules — no side effects,
 * no listeners. For event-driven orchestration see {@link FibreEngineStateMachine}.
 * </p>
 *
 * <h3>Legal Transitions</h3>
 * <pre>
 * UNINITIALIZED       → DISCOVERING_PEERS
 * DISCOVERING_PEERS   → CONNECTED_MESH | DISCONNECTED | ERROR
 * CONNECTED_MESH      → ROUTING | DISCONNECTED | ERROR
 * ROUTING             → CONNECTED_MESH | DISCONNECTED | ERROR
 * DISCONNECTED        → DISCOVERING_PEERS | ERROR
 * ERROR               → DISCONNECTED (recovery) | UNINITIALIZED (reset)
 * </pre>
 */
public class StateMachine {

    private static final Logger LOG = Logger.getLogger(StateMachine.class.getName());

    private final EnumMap<EngineState, Set<EngineState>> allowedTransitions;

    /** Builds the full transition matrix. */
    public StateMachine() {
        allowedTransitions = new EnumMap<>(EngineState.class);

        // UNINITIALIZED → DISCOVERING_PEERS
        addTransition(EngineState.UNINITIALIZED, EngineState.DISCOVERING_PEERS);

        // DISCOVERING_PEERS → CONNECTED_MESH | DISCONNECTED | ERROR
        addTransition(EngineState.DISCOVERING_PEERS, EngineState.CONNECTED_MESH);
        addTransition(EngineState.DISCOVERING_PEERS, EngineState.DISCONNECTED);
        addTransition(EngineState.DISCOVERING_PEERS, EngineState.ERROR);

        // CONNECTED_MESH → ROUTING | DISCONNECTED | ERROR
        addTransition(EngineState.CONNECTED_MESH, EngineState.ROUTING);
        addTransition(EngineState.CONNECTED_MESH, EngineState.DISCONNECTED);
        addTransition(EngineState.CONNECTED_MESH, EngineState.ERROR);

        // ROUTING → CONNECTED_MESH | DISCONNECTED | ERROR
        addTransition(EngineState.ROUTING, EngineState.CONNECTED_MESH);
        addTransition(EngineState.ROUTING, EngineState.DISCONNECTED);
        addTransition(EngineState.ROUTING, EngineState.ERROR);

        // DISCONNECTED → DISCOVERING_PEERS | ERROR
        addTransition(EngineState.DISCONNECTED, EngineState.DISCOVERING_PEERS);
        addTransition(EngineState.DISCONNECTED, EngineState.ERROR);

        // ERROR → DISCONNECTED (recovery attempt) | UNINITIALIZED (full reset)
        addTransition(EngineState.ERROR, EngineState.DISCONNECTED);
        addTransition(EngineState.ERROR, EngineState.UNINITIALIZED);
    }

    private void addTransition(EngineState from, EngineState to) {
        allowedTransitions.computeIfAbsent(from, k -> new HashSet<>()).add(to);
    }

    /**
     * Validates the transition and logs it.
     *
     * @param from the current state
     * @param to   the target state
     * @throws IllegalStateException if the transition is not in the allowed matrix
     */
    public void transition(EngineState from, EngineState to) {
        if (from == to) {
            return;
        }
        Set<EngineState> allowed = allowedTransitions.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalStateException(
                    "Invalid state transition: " + from + " -> " + to);
        }
        LOG.fine("Validated transition: " + from + " -> " + to);
    }

    /** Returns whether the transition is allowed. */
    public boolean isTransitionAllowed(EngineState from, EngineState to) {
        Set<EngineState> allowed = allowedTransitions.get(from);
        return allowed != null && allowed.contains(to);
    }

    /** Resets the transition rules (no-op; matrix is immutable after construction). */
    public void reset() {
        LOG.fine("State machine reset (no-op — matrix is immutable)");
    }
}
