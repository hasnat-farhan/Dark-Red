package com.antor.f2p.engine.core;

import com.antor.f2p.engine.api.FibrePacket;
import com.antor.f2p.engine.network.LinkMetrics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

/**
 * Offline persistence manager for the Wandering Fibre Engine.
 * <p>
 * Uses file-based binary storage to persist:
 * <ul>
 *   <li>Unsent/queued {@link FibrePacket}s when the engine is offline
 *       or has zero connected peers</li>
 *   <li>Known peer history and cached {@link LinkMetrics} for faster
 *       reconnection after a full restart</li>
 * </ul>
 * </p>
 *
 * <p>
 * When the engine transitions back to {@code CONNECTED_MESH}, call
 * {@link #flushQueuedPackets(java.util.function.Consumer)} to replay
 * all stored packets through the engine loop.
 * </p>
 */
public class FibreStore {

    private static final Logger LOG = Logger.getLogger(FibreStore.class.getName());

    private static final String STORE_DIR = "f2p-serverless/store";
    private static final String PACKET_FILE = STORE_DIR + "/queued_packets.dat";
    private static final String PEER_CACHE_FILE = STORE_DIR + "/peer_cache.dat";
    private static final String ROUTE_CACHE_FILE = STORE_DIR + "/route_cache.dat";
    private static final int MAX_PERSISTED_PACKETS = 500;

    private final ConcurrentLinkedQueue<StoredPacket> queuedPackets;
    private final ReentrantReadWriteLock rwLock;
    private final AtomicBoolean loaded;
    private final AtomicInteger persistCounter;

    private Map<String, LinkMetrics> peerCache;
    private Map<String, String> routeCache;

    public FibreStore() {
        this.queuedPackets = new ConcurrentLinkedQueue<>();
        this.rwLock = new ReentrantReadWriteLock();
        this.loaded = new AtomicBoolean(false);
        this.persistCounter = new AtomicInteger(0);
        this.peerCache = new HashMap<>();
        this.routeCache = new HashMap<>();
    }

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    /**
     * Loads previously persisted data from disk. Idempotent.
     */
    public void load() {
        if (loaded.getAndSet(true)) return;
        new File(STORE_DIR).mkdirs();
        loadPeerCache();
        loadRouteCache();
        loadQueuedPackets();
        LOG.info("FibreStore loaded (" + queuedPackets.size()
                + " queued packets, " + peerCache.size()
                + " cached peers, " + routeCache.size() + " cached routes)");
    }

    /**
     * Persists everything to disk and clears in-memory state.
     */
    public void flushAndClose() {
        savePeerCache();
        saveRouteCache();
        saveQueuedPackets();
        queuedPackets.clear();
        peerCache.clear();
        routeCache.clear();
        LOG.info("FibreStore flushed and closed");
    }

    // ---------------------------------------------------------------
    //  Packet queueing
    // ---------------------------------------------------------------

    /**
     * Queues a packet for later delivery when the engine is offline.
     *
     * @param packet the packet that could not be sent
     */
    public void queuePacket(FibrePacket packet) {
        if (queuedPackets.size() >= MAX_PERSISTED_PACKETS) {
            queuedPackets.poll(); // drop oldest
        }
        queuedPackets.offer(new StoredPacket(
                packet.getSourceNodeId(),
                packet.getDestinationNodeId(),
                packet.getSequenceNumber(),
                packet.getTimestamp(),
                packet.getPayloadType(),
                packet.getRawDataBuffer()
        ));
        LOG.fine("Packet queued for offline storage: " + packet.getSequenceNumber());

        // Periodic persist every 10 queued packets
        if (persistCounter.incrementAndGet() % 10 == 0) {
            saveQueuedPackets();
        }
    }

    /**
     * Returns the number of packets currently waiting to be flushed.
     */
    public int queuedPacketCount() {
        return queuedPackets.size();
    }

    /**
     * Drains all queued packets and feeds them to the given consumer.
     * Called when the engine transitions to {@code CONNECTED_MESH} or
     * {@code ROUTING}.
     *
     * @param packetConsumer receives each dequeued packet
     */
    public void flushQueuedPackets(java.util.function.Consumer<FibrePacket> packetConsumer) {
        int count = 0;
        StoredPacket sp;
        while ((sp = queuedPackets.poll()) != null) {
            FibrePacket packet = new FibrePacket(
                    sp.sourceNodeId, sp.destinationNodeId,
                    sp.sequenceNumber, sp.timestamp,
                    sp.payloadType, sp.rawDataBuffer
            );
            packetConsumer.accept(packet);
            count++;
        }
        // Clear the persisted file since all packets are replayed
        new File(PACKET_FILE).delete();
        if (count > 0) {
            LOG.info("Flushed " + count + " queued packets from offline storage");
        }
    }

    // ---------------------------------------------------------------
    //  Peer cache
    // ---------------------------------------------------------------

    /** Caches peer link metrics for faster reconnection. */
    public void cachePeerMetrics(Map<String, LinkMetrics> metrics) {
        rwLock.writeLock().lock();
        try {
            peerCache = new HashMap<>(metrics);
        } finally {
            rwLock.writeLock().unlock();
        }
        savePeerCache();
    }

    /** Returns the last-known peer metrics (empty map if none). */
    public Map<String, LinkMetrics> getCachedPeerMetrics() {
        rwLock.readLock().lock();
        try {
            return Collections.unmodifiableMap(peerCache);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------
    //  Route cache
    // ---------------------------------------------------------------

    /** Caches the route table for faster reconnection. */
    public void cacheRoutes(Map<String, String> routes) {
        rwLock.writeLock().lock();
        try {
            routeCache = new HashMap<>(routes);
        } finally {
            rwLock.writeLock().unlock();
        }
        saveRouteCache();
    }

    /** Returns the last-known route table (empty map if none). */
    public Map<String, String> getCachedRoutes() {
        rwLock.readLock().lock();
        try {
            return Collections.unmodifiableMap(routeCache);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------
    //  Serialization helpers (simple binary format)
    // ---------------------------------------------------------------

    private void loadQueuedPackets() {
        File f = new File(PACKET_FILE);
        if (!f.exists()) return;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                queuedPackets.offer(readStoredPacket(dis));
            }
        } catch (IOException e) {
            LOG.warning("Failed to load queued packets: " + e.getMessage());
        }
    }

    private void saveQueuedPackets() {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(PACKET_FILE)))) {
            dos.writeInt(queuedPackets.size());
            for (StoredPacket sp : queuedPackets) {
                writeStoredPacket(dos, sp);
            }
            dos.flush();
        } catch (IOException e) {
            LOG.warning("Failed to save queued packets: " + e.getMessage());
        }
    }

    private void loadPeerCache() {
        File f = new File(PEER_CACHE_FILE);
        if (!f.exists()) return;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            rwLock.writeLock().lock();
            try {
                int count = dis.readInt();
                peerCache = new HashMap<>(count);
                for (int i = 0; i < count; i++) {
                    String peerId = dis.readUTF();
                    long lastSeen = dis.readLong();
                    double latency = dis.readDouble();
                    double signal = dis.readDouble();
                    int hops = dis.readInt();
                    int loss = dis.readInt();
                    peerCache.put(peerId, new LinkMetrics(peerId, lastSeen, latency, signal, hops, loss));
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        } catch (IOException e) {
            LOG.warning("Failed to load peer cache: " + e.getMessage());
        }
    }

    private void savePeerCache() {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(PEER_CACHE_FILE)))) {
            rwLock.readLock().lock();
            try {
                dos.writeInt(peerCache.size());
                for (Map.Entry<String, LinkMetrics> e : peerCache.entrySet()) {
                    dos.writeUTF(e.getKey());
                    dos.writeLong(e.getValue().getLastSeenEpoch());
                    dos.writeDouble(e.getValue().getLatencyMs());
                    dos.writeDouble(e.getValue().getSignalStrength());
                    dos.writeInt(e.getValue().getHopCount());
                    dos.writeInt(e.getValue().getPacketLossPercent());
                }
            } finally {
                rwLock.readLock().unlock();
            }
            dos.flush();
        } catch (IOException e) {
            LOG.warning("Failed to save peer cache: " + e.getMessage());
        }
    }

    private void loadRouteCache() {
        File f = new File(ROUTE_CACHE_FILE);
        if (!f.exists()) return;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            rwLock.writeLock().lock();
            try {
                int count = dis.readInt();
                routeCache = new HashMap<>(count);
                for (int i = 0; i < count; i++) {
                    routeCache.put(dis.readUTF(), dis.readUTF());
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        } catch (IOException e) {
            LOG.warning("Failed to load route cache: " + e.getMessage());
        }
    }

    private void saveRouteCache() {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(ROUTE_CACHE_FILE)))) {
            rwLock.readLock().lock();
            try {
                dos.writeInt(routeCache.size());
                for (Map.Entry<String, String> e : routeCache.entrySet()) {
                    dos.writeUTF(e.getKey());
                    dos.writeUTF(e.getValue());
                }
            } finally {
                rwLock.readLock().unlock();
            }
            dos.flush();
        } catch (IOException e) {
            LOG.warning("Failed to save route cache: " + e.getMessage());
        }
    }

    private static StoredPacket readStoredPacket(DataInputStream dis) throws IOException {
        return new StoredPacket(
                dis.readUTF(),   // sourceNodeId
                dis.readUTF(),   // destinationNodeId
                dis.readLong(),  // sequenceNumber
                dis.readLong(),  // timestamp
                dis.readUTF(),   // payloadType
                readByteArray(dis) // rawDataBuffer
        );
    }

    private static void writeStoredPacket(DataOutputStream dos, StoredPacket sp) throws IOException {
        dos.writeUTF(sp.sourceNodeId);
        dos.writeUTF(sp.destinationNodeId);
        dos.writeLong(sp.sequenceNumber);
        dos.writeLong(sp.timestamp);
        dos.writeUTF(sp.payloadType);
        writeByteArray(dos, sp.rawDataBuffer);
    }

    private static byte[] readByteArray(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        byte[] buf = new byte[len];
        dis.readFully(buf);
        return buf;
    }

    private static void writeByteArray(DataOutputStream dos, byte[] buf) throws IOException {
        dos.writeInt(buf.length);
        dos.write(buf);
    }

    // ---------------------------------------------------------------
    //  Internal value type
    // ---------------------------------------------------------------

    private static final class StoredPacket {
        final String sourceNodeId;
        final String destinationNodeId;
        final long sequenceNumber;
        final long timestamp;
        final String payloadType;
        final byte[] rawDataBuffer;

        StoredPacket(String src, String dst, long seq, long ts, String type, byte[] data) {
            this.sourceNodeId = src;
            this.destinationNodeId = dst;
            this.sequenceNumber = seq;
            this.timestamp = ts;
            this.payloadType = type;
            this.rawDataBuffer = data;
        }
    }
}
