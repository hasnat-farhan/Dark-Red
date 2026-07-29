package com.antor.f2p.engine.api;

import java.util.Collections;
import java.util.List;

/**
 * Immutable telemetry snapshot exposed by {@link WanderingFibreEngine} so
 * SOSBlue can monitor mesh health in real time.
 */
public final class MeshHealthSnapshot {

    private final int activeNodeCount;
    private final double averageLatencyMs;
    private final double averageSignalStrength;
    private final int activeRouteCount;
    private final long totalPacketsOutbound;
    private final long totalPacketsInbound;
    private final long totalPacketsForwarded;
    private final long totalPacketsDropped;
    private final EngineState currentState;
    private final List<String> knownPeers;

    public MeshHealthSnapshot(int activeNodeCount,
                              double averageLatencyMs,
                              double averageSignalStrength,
                              int activeRouteCount,
                              long totalPacketsOutbound,
                              long totalPacketsInbound,
                              long totalPacketsForwarded,
                              long totalPacketsDropped,
                              EngineState currentState,
                              List<String> knownPeers) {
        this.activeNodeCount = activeNodeCount;
        this.averageLatencyMs = averageLatencyMs;
        this.averageSignalStrength = averageSignalStrength;
        this.activeRouteCount = activeRouteCount;
        this.totalPacketsOutbound = totalPacketsOutbound;
        this.totalPacketsInbound = totalPacketsInbound;
        this.totalPacketsForwarded = totalPacketsForwarded;
        this.totalPacketsDropped = totalPacketsDropped;
        this.currentState = currentState;
        this.knownPeers = knownPeers != null
                ? Collections.unmodifiableList(knownPeers)
                : Collections.emptyList();
    }

    public int getActiveNodeCount()                 { return activeNodeCount; }
    public double getAverageLatencyMs()             { return averageLatencyMs; }
    public double getAverageSignalStrength()        { return averageSignalStrength; }
    public int getActiveRouteCount()                { return activeRouteCount; }
    public long getTotalPacketsOutbound()           { return totalPacketsOutbound; }
    public long getTotalPacketsInbound()            { return totalPacketsInbound; }
    public long getTotalPacketsForwarded()          { return totalPacketsForwarded; }
    public long getTotalPacketsDropped()            { return totalPacketsDropped; }
    public EngineState getCurrentState()            { return currentState; }
    public List<String> getKnownPeers()             { return knownPeers; }

    @Override
    public String toString() {
        return "MeshHealth{" +
                "nodes=" + activeNodeCount +
                ", avgLat=" + String.format("%.1f", averageLatencyMs) + "ms" +
                ", routes=" + activeRouteCount +
                ", state=" + currentState +
                ", out=" + totalPacketsOutbound +
                ", in=" + totalPacketsInbound +
                ", fwd=" + totalPacketsForwarded +
                ", dropped=" + totalPacketsDropped +
                '}';
    }
}
