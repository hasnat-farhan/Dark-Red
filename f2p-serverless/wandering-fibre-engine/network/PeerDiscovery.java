package com.antor.f2p.engine.network;

import java.util.Collections;
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
     * Removes a peer from the discovery table.
     *
     * @param peerId the peer to remove
     */
    public void removePeer(String peerId) {
        peers.remove(peerId);
        LOG.fine("Peer removed: " + peerId);
    }

    /**
     * Returns the set of peer IDs known at this moment.
     */
    public Set<String> getKnownPeers() {
        return Collections.unmodifiableSet(peers.keySet());
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
