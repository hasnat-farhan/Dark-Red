package com.antor.f2p.engine.network;

import com.antor.f2p.engine.api.FibrePacket;

import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Enhanced routing table that combines static routes with dynamically learned
 * paths from {@link FibrePathRouter} and tracks per-link {@link LinkMetrics}.
 * <p>
 * Every route entry carries a composite weight computed from latency, signal
 * strength, hop count, and packet loss — the path router uses these to select
 * optimal next-hops.
 * </p>
 */
public class RoutingTable {

    private static final Logger LOG = Logger.getLogger(RoutingTable.class.getName());

    private final ConcurrentHashMap<String, RouteEntry> staticRoutes;
    private final ConcurrentHashMap<String, LinkMetrics> linkMetrics;
    private final PeerDiscovery peerDiscovery;
    private final FibrePathRouter pathRouter;

    // Packet tracking counters
    private final AtomicLong outboundCounter;
    private final AtomicLong inboundCounter;
    private final AtomicLong forwardedCounter;
    private final AtomicLong droppedCounter;

    public RoutingTable(PeerDiscovery peerDiscovery, String localNodeId) {
        this.staticRoutes = new ConcurrentHashMap<>();
        this.linkMetrics = new ConcurrentHashMap<>();
        this.peerDiscovery = peerDiscovery;
        this.pathRouter = new FibrePathRouter(localNodeId, peerDiscovery);
        this.outboundCounter = new AtomicLong(0);
        this.inboundCounter = new AtomicLong(0);
        this.forwardedCounter = new AtomicLong(0);
        this.droppedCounter = new AtomicLong(0);
    }

    // ---------------------------------------------------------------
    //  Route management
    // ---------------------------------------------------------------

    public void addRoute(String targetPeerId, String nextHop, int metric) {
        staticRoutes.put(targetPeerId, new RouteEntry(targetPeerId, nextHop, metric));
        // Seed a default LinkMetrics entry so the path router knows about this peer
        linkMetrics.putIfAbsent(targetPeerId,
                new LinkMetrics(targetPeerId, System.currentTimeMillis(),
                        10.0, 0.8, metric, 5));
        pathRouter.updateLinkMetrics(targetPeerId, linkMetrics.get(targetPeerId));
        LOG.fine("Route added: " + targetPeerId + " -> " + nextHop + " (metric=" + metric + ")");
    }

    public void removeRoute(String targetPeerId) {
        staticRoutes.remove(targetPeerId);
        linkMetrics.remove(targetPeerId);
        pathRouter.removePeer(targetPeerId);
    }

    /**
     * Resolves the next-hop for a destination, consulting static routes first,
     * then falling back to the dynamic path router.
     */
    public Optional<String> resolveNextHop(String targetPeerId) {
        // Static routes take priority
        RouteEntry staticEntry = staticRoutes.get(targetPeerId);
        if (staticEntry != null) {
            return Optional.of(staticEntry.nextHop);
        }
        // Dynamic routing via FibrePathRouter
        return pathRouter.resolveNextHop(targetPeerId);
    }

    /**
     * Updates link metrics for a peer and re-triggers route recomputation.
     */
    public void updateLinkMetrics(String peerId, double latencyMs,
                                  double signalStrength, int hopCount,
                                  int packetLossPercent) {
        LinkMetrics metrics = new LinkMetrics(peerId, System.currentTimeMillis(),
                latencyMs, signalStrength, hopCount, packetLossPercent);
        linkMetrics.put(peerId, metrics);
        pathRouter.updateLinkMetrics(peerId, metrics);
    }

    // ---------------------------------------------------------------
    //  Packet tracking
    // ---------------------------------------------------------------

    public void recordOutboundPacket(FibrePacket packet) {
        outboundCounter.incrementAndGet();
        pathRouter.registerForAck(packet, () -> {
            LOG.warning("Packet seq=" + packet.getSequenceNumber()
                    + " not acknowledged — initiating reroute");
            pathRouter.removePeer(packet.getDestinationNodeId());
        });
    }

    public void recordInboundPacket(FibrePacket packet) {
        inboundCounter.incrementAndGet();
    }

    public void recordForwardedPacket(FibrePacket packet) {
        forwardedCounter.incrementAndGet();
    }

    public void recordDroppedPacket(FibrePacket packet) {
        droppedCounter.incrementAndGet();
        LOG.warning("Packet " + packet.getSequenceNumber() + " dropped");
    }

    // ---------------------------------------------------------------
    //  Topology change callback
    // ---------------------------------------------------------------

    public void onTopologyChange(Runnable callback) {
        pathRouter.onTopologyChange(callback);
    }

    // ---------------------------------------------------------------
    //  Statistics
    // ---------------------------------------------------------------

    public long getOutboundCount()   { return outboundCounter.get(); }
    public long getInboundCount()    { return inboundCounter.get(); }
    public long getForwardedCount()  { return forwardedCounter.get(); }
    public long getDroppedCount()    { return droppedCounter.get(); }

    /** Returns the dynamic path router (for external wiring). */
    public FibrePathRouter getPathRouter() {
        return pathRouter;
    }

    /** Returns all registered link metrics. */
    public Map<String, LinkMetrics> getAllLinkMetrics() {
        return Collections.unmodifiableMap(linkMetrics);
    }

    /** Returns the average latency across all live links (0.0 if none). */
    public double getAverageLatencyMs() {
        Collection<LinkMetrics> metrics = linkMetrics.values();
        if (metrics.isEmpty()) return 0.0;
        return metrics.stream()
                .filter(m -> m.isAlive(FibrePathRouter.LINK_TTL_MS))
                .mapToDouble(LinkMetrics::getLatencyMs)
                .average()
                .orElse(0.0);
    }

    /** Returns the average signal strength across all live links (0.0 if none). */
    public double getAverageSignalStrength() {
        Collection<LinkMetrics> metrics = linkMetrics.values();
        if (metrics.isEmpty()) return 0.0;
        return metrics.stream()
                .filter(m -> m.isAlive(FibrePathRouter.LINK_TTL_MS))
                .mapToDouble(LinkMetrics::getSignalStrength)
                .average()
                .orElse(0.0);
    }

    /** Returns the composite view of all routes (static + dynamic). */
    public Map<String, String> getAllRoutes() {
        Map<String, String> all = new HashMap<>();
        staticRoutes.forEach((id, entry) -> all.put(id, entry.nextHop));
        all.putAll(pathRouter.getAllRoutes());
        return Collections.unmodifiableMap(all);
    }

    /** Clears everything. */
    public void clear() {
        staticRoutes.clear();
        linkMetrics.clear();
        outboundCounter.set(0);
        inboundCounter.set(0);
        forwardedCounter.set(0);
        droppedCounter.set(0);
        LOG.fine("Routing table cleared");
    }

    // ---------------------------------------------------------------
    //  Internal
    // ---------------------------------------------------------------

    private static final class RouteEntry {
        final String targetPeerId;
        final String nextHop;
        final int metric;
        RouteEntry(String targetPeerId, String nextHop, int metric) {
            this.targetPeerId = targetPeerId;
            this.nextHop = nextHop;
            this.metric = metric;
        }
    }
}
