package com.antor.f2p.engine.network;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Serverless node discovery module responsible for tracking known peers
 * in the Wandering Fibre Engine's peer-to-peer overlay network.
 * <p>
 * In a serverless environment peers are ephemeral; this module provides
 * eventual-consistency discovery via heartbeats and TTL-based eviction.
 * </p>
 * <p>
 * <strong>Network-switching support:</strong> Each peer now stores its
 * IP endpoint separately so routes can be updated dynamically when a
 * network change occurs. The {@link #clearAllEndpoints()} method allows
 * all cached IPs to be flushed when the local network changes, forcing
 * re-discovery via heartbeats.</p>
 */
public class PeerDiscovery {

    private static final Logger LOG = Logger.getLogger(PeerDiscovery.class.getName());

    private final ConcurrentHashMap<String, PeerInfo> peers;

    public PeerDiscovery() {
        this.peers = new ConcurrentHashMap<>();
    }

    /**
     * Initialises the peer discovery subsystem. Called during engine bootstrap.
     */
    public void initialize() {
        LOG.fine("PeerDiscovery initialised");
    }

    /**
     * Registers or refreshes a known peer.
     *
     * @param peerId   unique identifier for the peer
     * @param endpoint network endpoint (e.g. "host:port")
     */
    public void announcePeer(String peerId, String endpoint) {
        PeerInfo info = new PeerInfo(peerId, endpoint, System.currentTimeMillis());
        peers.put(peerId, info);
        LOG.fine("Peer announced: " + peerId + " @ " + endpoint);
    }

    /**
     * Updates the endpoint for an existing peer without resetting its
     * last-seen timestamp (used when we receive a direct message from
     * a known peer at a new IP).
     *
     * @param peerId   the existing peer's ID
     * @param endpoint the new network endpoint (e.g. "192.168.1.42:41234")
     * @return {@code true} if the peer existed and was updated
     */
    public boolean updatePeerEndpoint(String peerId, String endpoint) {
        PeerInfo existing = peers.get(peerId);
        if (existing != null) {
            // Create a new PeerInfo with the same lastSeen but new endpoint
            PeerInfo updated = new PeerInfo(peerId, endpoint, existing.lastSeen);
            peers.put(peerId, updated);
            LOG.fine("Peer endpoint updated: " + peerId + " → " + endpoint);
            return true;
        }
        return false;
    }

    /**
     * Removes a peer from the discovery table.
     *
     * @param peerId the peer to remove
     */
    public void removePeer(String peerId) {
        peers.remove(peerId);
        LOG.fine("Peer removed: " + peerId);
    }

    /**
     * Clears all cached peer IP endpoints without removing the peers
     * themselves. This is called when the local network changes; peers
     * will be re-discovered via heartbeat or direct message.
     */
    public void clearAllEndpoints() {
        for (Map.Entry<String, PeerInfo> entry : peers.entrySet()) {
            PeerInfo info = entry.getValue();
            // Reset endpoint to a placeholder; re-discovery will fill it
            PeerInfo cleared = new PeerInfo(info.peerId, "", info.lastSeen);
            peers.put(entry.getKey(), cleared);
        }
        LOG.info("Cleared all peer endpoints after network change");
    }

    /**
     * Returns the endpoint string for a given peer, or null if unknown.
     */
    public String getPeerEndpoint(String peerId) {
        PeerInfo info = peers.get(peerId);
        return info != null ? info.endpoint : null;
    }

    /**
     * Returns the set of peer IDs known at this moment.
     */
    public Set<String> getKnownPeers() {
        return Collections.unmodifiableSet(peers.keySet());
    }

    /**
     * Returns true if at least one peer is known.
     */
    public boolean hasPeers() {
        return !peers.isEmpty();
    }

    /**
     * Returns the number of known peers.
     */
    public int peerCount() {
        return peers.size();
    }

    /**
     * Evicts peers whose last heartbeat is older than {@code ttlMillis}.
     *
     * @param ttlMillis time-to-live in milliseconds
     */
    public void evictStalePeers(long ttlMillis) {
        long cutoff = System.currentTimeMillis() - ttlMillis;
        peers.values().removeIf(info -> info.lastSeen < cutoff);
    }

    /** Shuts down peer discovery. */
    public void shutdown() {
        peers.clear();
        LOG.fine("PeerDiscovery shut down");
    }

    // ---------------------------------------------------------------
    //  Internal value type
    // ---------------------------------------------------------------

    private static final class PeerInfo {
        final String peerId;
        final String endpoint;
        final long lastSeen;

        PeerInfo(String peerId, String endpoint, long lastSeen) {
            this.peerId = peerId;
            this.endpoint = endpoint;
            this.lastSeen = lastSeen;
        }
    }
}
