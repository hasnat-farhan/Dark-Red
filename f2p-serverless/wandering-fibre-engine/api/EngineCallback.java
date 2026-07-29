package com.antor.f2p.engine.api;

/**
 * Callback interface for receiving signals, packets, state changes,
 * diagnostics, and error boundary events from the Wandering Fibre Engine.
 * <p>
 * Register implementations via {@link WanderingFibreEngine#registerListener(EngineCallback)}
 * or {@code F2PBridge.registerListener(EngineCallback)}.
 * </p>
 *
 * <h3>Error Boundary</h3>
 * All engine-internal exceptions are caught by {@link WanderingFibreEngine}
 * and surfaced through {@link #onEngineError(int, String, Throwable)}.
 * Implementors <strong>must not throw</strong> from any callback — the engine
 * wraps every listener invocation in a try/catch so a misbehaving listener
 * never crashes the engine.
 */
@FunctionalInterface
public interface EngineCallback {

    // ---------------------------------------------------------------
    //  Required
    // ---------------------------------------------------------------

    /**
     * Invoked when the engine emits a signal (high-level event).
     *
     * @param signal the incoming fibre signal that triggered the callback
     */
    void onSignal(FibreSignal signal);

    // ---------------------------------------------------------------
    //  Default — packet / state / error
    // ---------------------------------------------------------------

    /** Invoked when a raw {@link FibrePacket} arrives at the local node. */
    default void onPacketReceived(FibrePacket packet) {}

    /**
     * Invoked when the engine's mesh lifecycle state changes.
     *
     * @param newState the new engine state as a display string
     */
    default void onStateChanged(String newState) {}

    /**
     * Invoked when the engine's lifecycle state changes (typed overload).
     * Default implementation delegates to {@link #onStateChanged(String)}.
     */
    default void onStateChanged(EngineState previous, EngineState current) {
        onStateChanged(current.name());
    }

    /** Invoked when the engine encounters a non-fatal error. */
    default void onError(Throwable throwable) {}

    // ---------------------------------------------------------------
    //  Phase 4 — Error Boundary & Diagnostics
    // ---------------------------------------------------------------

    /**
     * Invoked when the engine catches an unexpected runtime exception
     * inside its internal execution loop or subsystem.
     * <p>
     * The engine never propagates the exception to SOSBlue — it is
     * caught, logged, and surfaced through this callback as a status
     * code. The engine continues running in degraded mode unless the
     * status code indicates a fatal condition.
     * </p>
     *
     * @param statusCode  a numeric code categorising the error
     * @param message     a human-readable description
     * @param cause       the original exception (may be null)
     */
    default void onEngineError(int statusCode, String message, Throwable cause) {}

    /**
     * Invoked periodically (or on demand) with a diagnostic snapshot
     * that SOSBlue can use for network monitoring.
     *
     * @param diagnostics a formatted diagnostic string
     */
    default void onDiagnostics(String diagnostics) {}
}
