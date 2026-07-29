package com.antor.f2p.engine.api;

/**
 * Represents the lifecycle states of the Wandering Fibre Engine's mesh network.
 * <p>
 * The engine transitions through these states as it discovers peers, forms
 * a mesh, begins routing, and handles disconnections or errors.
 * </p>
 */
public enum EngineState {
    /** Engine has not been initialized. */
    UNINITIALIZED,
    /** Engine is actively discovering nearby peers via heartbeat / broadcast. */
    DISCOVERING_PEERS,
    /** A mesh of peers has been established; engine is connected. */
    CONNECTED_MESH,
    /** Engine is actively routing fibre packets across the mesh. */
    ROUTING,
    /** Engine has been disconnected from the mesh (may attempt reconnection). */
    DISCONNECTED,
    /** Engine encountered a fatal error and cannot continue normal operation. */
    ERROR
}
