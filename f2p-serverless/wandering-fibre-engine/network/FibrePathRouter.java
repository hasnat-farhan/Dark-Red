package com.antor.f2p.engine.network;

import com.antor.f2p.engine.api.FibrePacket;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Dynamic path router that calculates optimal multi-hop routes through the
 * mesh using a weighted Dijkstra shortest-path algorithm.
 * <p>
 * <strong>Features:</strong>
 * <ul>
 *   <li>Latency / signal-strength / hop-count / packet-loss composite weights</li>
 *   <li>Automatic route recalculation when peers drop or new peers appear</li>
 *   <li>ACK/NACK tracking with exponential backoff retry for unacknowledged packets</li>
 *   <li>Per-destination next-hop routing with fallback</li>
 * </ul>
 * </p>
 */
public class FibrePathRouter {

    private static final Logger LOG = Logger.getLogger(FibrePathRouter.class.getName());

    /** Default TTL for a link before it's considered dead. */
    public static final long LINK_TTL_MS = 10_000;

    /** Default number of routing table recompute cycles. */
    public static final int MAX_RETRY_ATTEMPTS = 5;

    /** Base backoff delay (ms) — doubles each attempt. */
    public static final long BASE_BACKOFF_MS = 200;

    private final String localNodeId;
    private final PeerDiscovery peerDiscovery;
    private final ConcurrentHashMap<String, LinkMetrics> linkMetricsMap;
    private final ConcurrentHashMap<String, RouteCacheEntry> routeCache;

    // ACK tracking: packet sequence → pending ACK state
    private final ConcurrentHashMap<Long, PendingAck> pendingAcks;
    private final ConcurrentLinkedQueue<Runnable> topologyChangeCallbacks;

    /**
     * @param localNodeId   this node's identifier
     * @param peerDiscovery peer registry to query for live peers
     */
    public FibrePathRouter(String localNodeId, PeerDiscovery peerDiscovery) {
        this.localNodeId = Objects.requireNonNull(localNodeId);
        this.peerDiscovery = Objects.requireNonNull(peerDiscovery);
        this.linkMetricsMap = new ConcurrentHashMap<>();
        this.routeCache = new ConcurrentHashMap<>();
        this.pendingAcks = new ConcurrentHashMap<>();
        this.topologyChangeCallbacks = new ConcurrentLinkedQueue<>();
    }

    // ---------------------------------------------------------------
    //  Route management
    // ---------------------------------------------------------------

    /**
     * Returns the next-hop node for the given destination.
     * Recalculates the routing table if no cached route exists.
     *
     * @param destinationNodeId the target peer
     * @return next-hop node id, or empty if unreachable
     */
    public Optional<String> resolveNextHop(String destinationNodeId) {
        // Direct peer
        if (linkMetricsMap.containsKey(destinationNodeId) && isAlive(destinationNodeId)) {
            return Optional.of(destinationNodeId);
        }
        // Cached route
        String cached = routeCache.get(destinationNodeId) != null
                ? routeCache.get(destinationNodeId).nextHop : null;
        if (cached != null && isAlive(cached)) {
            return Optional.of(cached);
        }
        // Recompute and retry
        recomputeRoutes();
        RouteCacheEntry entry = routeCache.get(destinationNodeId);
        if (entry != null && isAlive(entry.nextHop)) {
            return Optional.of(entry.nextHop);
        }
        return Optional.empty();
    }

    /**
     * Returns all known routes as a map of destination → next-hop.
     */
    public Map<String, String> getAllRoutes() {
        Map<String, String> all = new HashMap<>();
        // Direct peers
        for (String peer : linkMetricsMap.keySet()) {
            if (isAlive(peer)) {
                all.put(peer, peer);
            }
        }
        // Cached multi-hop routes
        routeCache.forEach((dest, entry) -> {
            if (isAlive(entry.nextHop)) {
                all.put(dest, entry.nextHop + " (via " + entry.path + ")");
            }
        });
        return Collections.unmodifiableMap(all);
    }

    // ---------------------------------------------------------------
    //  Link metrics
    // ---------------------------------------------------------------

    /**
     * Updates or registers a link metric for a peer.
     *
     * @param peerId  the peer node
     * @param metrics updated metrics
     */
    public void updateLinkMetrics(String peerId, LinkMetrics metrics) {
        linkMetricsMap.put(peerId, metrics);
        recomputeRoutes();
        fireTopologyChange();
    }

    /**
     * Removes a peer (and all routes through it) when it drops.
     *
     * @param peerId the peer that disconnected
     */
    public void removePeer(String peerId) {
        linkMetricsMap.remove(peerId);
        routeCache.remove(peerId);
        recomputeRoutes();
        fireTopologyChange();
        LOG.info("Peer removed from routing: " + peerId);
    }

    /** Returns the live set of peer IDs with active links. */
    public Set<String> getAlivePeers() {
        return linkMetricsMap.entrySet().stream()
                .filter(e -> e.getValue().isAlive(LINK_TTL_MS))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** Returns all known link metrics. */
    public Collection<LinkMetrics> getAllLinkMetrics() {
        return Collections.unmodifiableCollection(linkMetricsMap.values());
    }

    // ---------------------------------------------------------------
    //  Dijkstra shortest-path
    // ---------------------------------------------------------------

    /**
     * Recomputes the route cache from the local node to every reachable
     * destination using Dijkstra's algorithm with composite link costs.
     */
    public void recomputeRoutes() {
        Set<String> nodes = new HashSet<>(linkMetricsMap.keySet());
        nodes.add(localNodeId);

        // Build adjacency: every pair of alive peers is a potential edge
        Map<String, Map<String, Double>> graph = new HashMap<>();
        for (String n : nodes) {
            graph.put(n, new HashMap<>());
        }

        // In a full mesh every peer can talk to every other peer directly;
        // edge weight = cost of the destination's link metrics.
        List<String> alivePeers = new ArrayList<>(getAlivePeers());
        for (int i = 0; i < alivePeers.size(); i++) {
            String a = alivePeers.get(i);
            if (!linkMetricsMap.containsKey(a)) continue;
            double costA = linkMetricsMap.get(a).computeCost();
            // Edge from local to peer
            graph.get(localNodeId).put(a, costA);
            // Edge from peer to local
            graph.get(a).put(localNodeId, costA);

            for (int j = i + 1; j < alivePeers.size(); j++) {
                String b = alivePeers.get(j);
                if (!linkMetricsMap.containsKey(b)) continue;
                double costB = linkMetricsMap.get(b).computeCost();
                double avg = (costA + costB) / 2.0;
                graph.get(a).put(b, avg);
                graph.get(b).put(a, avg);
            }
        }

        // Run Dijkstra
        Map<String, DijkstraResult> result = dijkstra(graph, localNodeId);

        // Build route cache
        routeCache.clear();
        for (Map.Entry<String, DijkstraResult> entry : result.entrySet()) {
            String dest = entry.getKey();
            if (dest.equals(localNodeId)) continue;
            DijkstraResult dr = entry.getValue();
            if (dr.distance == Double.MAX_VALUE) continue;

            // Extract the first hop from the path
            List<String> path = dr.path;
            String nextHop = path.size() >= 2 ? path.get(1) : dest;
            routeCache.put(dest, new RouteCacheEntry(nextHop, path, dr.distance));
        }

        LOG.fine("Routes recomputed: " + routeCache.size() + " destinations reachable");
    }

    /** Returns the number of active routes in the cache. */
    public int getActiveRouteCount() {
        return (int) routeCache.values().stream()
                .filter(e -> isAlive(e.nextHop))
                .count();
    }

    // ---------------------------------------------------------------
    //  ACK / NACK + Exponential Backoff
    // ---------------------------------------------------------------

    /**
     * Registers a packet that needs acknowledgement.
     *
     * @param packet    the outbound packet
     * @param onTimeout runnable to invoke if all retries are exhausted
     */
    public void registerForAck(FibrePacket packet, Runnable onTimeout) {
        pendingAcks.put(packet.getSequenceNumber(),
                new PendingAck(packet, 0, System.currentTimeMillis(), onTimeout));
    }

    /**
     * Processes an incoming ACK for the given packet sequence number,
     * removing it from the pending set.
     */
    public void processAck(long sequenceNumber) {
        PendingAck removed = pendingAcks.remove(sequenceNumber);
        if (removed != null) {
            LOG.fine("ACK received for packet seq=" + sequenceNumber);
        }
    }

    /**
     * Processes an incoming NACK, triggering a retry with exponential backoff.
     *
     * @param sequenceNumber the negatively acknowledged packet
     */
    public void processNack(long sequenceNumber) {
        PendingAck ack = pendingAcks.get(sequenceNumber);
        if (ack == null) return;
        if (ack.attempts >= MAX_RETRY_ATTEMPTS) {
            pendingAcks.remove(sequenceNumber);
            LOG.warning("Packet seq=" + sequenceNumber + " failed after "
                    + MAX_RETRY_ATTEMPTS + " attempts");
            if (ack.onTimeout != null) {
                try { ack.onTimeout.run(); } catch (Exception e) {
                    LOG.log(Level.WARNING, "Timeout handler failed", e);
                }
            }
            return;
        }
        // Exponential backoff
        ack.attempts++;
        long delay = BASE_BACKOFF_MS * (1L << (ack.attempts - 1)); // 200, 400, 800, 1600, 3200
        ack.lastAttemptTime = System.currentTimeMillis();
        LOG.fine("Retry " + ack.attempts + " for seq=" + sequenceNumber
                + " in " + delay + "ms");
        // The retry itself is delegated to the caller; we just track state.
    }

    /** Returns the number of packets currently awaiting ACK. */
    public int getPendingAckCount() {
        return pendingAcks.size();
    }

    // ---------------------------------------------------------------
    //  Topology change listeners
    // ---------------------------------------------------------------

    /** Registers a callback fired when the topology changes. */
    public void onTopologyChange(Runnable callback) {
        topologyChangeCallbacks.add(callback);
    }

    private void fireTopologyChange() {
        for (Runnable r : topologyChangeCallbacks) {
            try { r.run(); } catch (Exception e) {
                LOG.log(Level.WARNING, "Topology callback threw", e);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private boolean isAlive(String peerId) {
        LinkMetrics m = linkMetricsMap.get(peerId);
        return m != null && m.isAlive(LINK_TTL_MS);
    }

    private static Map<String, DijkstraResult> dijkstra(
            Map<String, Map<String, Double>> graph, String source) {

        Map<String, Double> distances = new HashMap<>();
        Map<String, String> predecessors = new HashMap<>();
        Set<String> settled = new HashSet<>();
        PriorityQueue<NodeDist> pq = new PriorityQueue<>(
                Comparator.comparingDouble(nd -> nd.distance));

        for (String node : graph.keySet()) {
            distances.put(node, Double.MAX_VALUE);
        }
        distances.put(source, 0.0);
        pq.add(new NodeDist(source, 0.0));

        while (!pq.isEmpty()) {
            NodeDist current = pq.poll();
            String u = current.node;
            if (settled.contains(u)) continue;
            settled.add(u);

            Map<String, Double> neighbors = graph.get(u);
            if (neighbors == null) continue;

            for (Map.Entry<String, Double> edge : neighbors.entrySet()) {
                String v = edge.getKey();
                if (settled.contains(v)) continue;
                double newDist = distances.get(u) + edge.getValue();
                if (newDist < distances.get(v)) {
                    distances.put(v, newDist);
                    predecessors.put(v, u);
                    pq.add(new NodeDist(v, newDist));
                }
            }
        }

        // Build paths
        Map<String, DijkstraResult> results = new HashMap<>();
        for (String node : graph.keySet()) {
            if (node.equals(source)) continue;
            double dist = distances.getOrDefault(node, Double.MAX_VALUE);
            List<String> path = new ArrayList<>();
            if (dist < Double.MAX_VALUE) {
                String cur = node;
                while (cur != null) {
                    path.add(cur);
                    cur = predecessors.get(cur);
                }
                Collections.reverse(path);
            }
            results.put(node, new DijkstraResult(dist, path));
        }
        return results;
    }

    // ---------------------------------------------------------------
    //  Internal types
    // ---------------------------------------------------------------

    private static final class NodeDist {
        final String node;
        final double distance;
        NodeDist(String node, double distance) {
            this.node = node;
            this.distance = distance;
        }
    }

    private static final class DijkstraResult {
        final double distance;
        final List<String> path;
        DijkstraResult(double distance, List<String> path) {
            this.distance = distance;
            this.path = path;
        }
    }

    static final class RouteCacheEntry {
        final String nextHop;
        final List<String> path;
        final double cost;
        RouteCacheEntry(String nextHop, List<String> path, double cost) {
            this.nextHop = nextHop;
            this.path = path;
            this.cost = cost;
        }
    }

    static final class PendingAck {
        final FibrePacket packet;
        int attempts;
        long lastAttemptTime;
        final Runnable onTimeout;

        PendingAck(FibrePacket packet, int attempts, long lastAttemptTime, Runnable onTimeout) {
            this.packet = packet;
            this.attempts = attempts;
            this.lastAttemptTime = lastAttemptTime;
            this.onTimeout = onTimeout;
        }
    }
}
