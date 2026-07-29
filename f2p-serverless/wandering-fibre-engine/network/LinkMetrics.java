package com.antor.f2p.engine.network;

import java.util.Objects;

/**
 * Immutable snapshot of link quality metrics between the local node and
 * a discovered peer.
 * <p>
 * These metrics feed into {@link FibrePathRouter}'s cost calculation to
 * select the optimal next-hop for each packet.
 * </p>
 */
public final class LinkMetrics {

    /** Default weight when a new peer is first seen. */
    public static final double DEFAULT_WEIGHT = 10.0;

    private final String peerId;
    private final long lastSeenEpoch;
    private final double latencyMs;          // round-trip estimate
    private final double signalStrength;     // 0.0 (none) … 1.0 (perfect)
    private final int hopCount;              // distance from local node
    private final int packetLossPercent;     // 0–100

    public LinkMetrics(String peerId, long lastSeenEpoch,
                       double latencyMs, double signalStrength,
                       int hopCount, int packetLossPercent) {
        this.peerId = Objects.requireNonNull(peerId);
        this.lastSeenEpoch = lastSeenEpoch;
        this.latencyMs = Math.max(0, latencyMs);
        this.signalStrength = Math.max(0, Math.min(1.0, signalStrength));
        this.hopCount = Math.max(1, hopCount);
        this.packetLossPercent = Math.max(0, Math.min(100, packetLossPercent));
    }

    // ---------------------------------------------------------------
    //  Getters
    // ---------------------------------------------------------------

    public String getPeerId()                    { return peerId; }
    public long getLastSeenEpoch()               { return lastSeenEpoch; }
    public double getLatencyMs()                 { return latencyMs; }
    public double getSignalStrength()            { return signalStrength; }
    public int getHopCount()                     { return hopCount; }
    public int getPacketLossPercent()            { return packetLossPercent; }

    // ---------------------------------------------------------------
    //  Composite cost — lower is better
    // ---------------------------------------------------------------

    /**
     * Computes a normalised composite cost used by the path router.
     * <p>
     * Formula: {@code (latencyMs * 0.3) + ((1 - signalStrength) * 50 * 0.3)
     *           + (hopCount * 5 * 0.2) + (packetLossPercent * 0.2)}
     * </p>
     */
    public double computeCost() {
        double latencyCost   = latencyMs * 0.3;
        double signalCost    = (1.0 - signalStrength) * 50.0 * 0.3;
        double hopCost       = hopCount * 5.0 * 0.2;
        double lossCost      = packetLossPercent * 0.2;
        return latencyCost + signalCost + hopCost + lossCost;
    }

    /** Returns true if the link is considered alive (seen within ttl). */
    public boolean isAlive(long ttlMillis) {
        return (System.currentTimeMillis() - lastSeenEpoch) < ttlMillis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LinkMetrics)) return false;
        LinkMetrics that = (LinkMetrics) o;
        return peerId.equals(that.peerId);
    }

    @Override
    public int hashCode() { return peerId.hashCode(); }

    @Override
    public String toString() {
        return "LinkMetrics{peer='" + peerId + '\''
                + ", lat=" + String.format("%.1f", latencyMs) + "ms"
                + ", sig=" + String.format("%.2f", signalStrength)
                + ", hops=" + hopCount
                + ", loss=" + packetLossPercent + "%"
                + '}';
    }
}
