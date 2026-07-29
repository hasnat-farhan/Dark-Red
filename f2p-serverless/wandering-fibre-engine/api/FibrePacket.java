package com.antor.f2p.engine.api;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-level data packet that flows through the Wandering Fibre Engine's mesh.
 * <p>
 * This is the fundamental unit of communication between nodes. Each packet
 * carries source/destination node identifiers, a monotonically increasing
 * sequence number, a timestamp, a payload type discriminator, and the raw
 * binary data buffer.
 * </p>
 */
public final class FibrePacket {

    private static final AtomicLong SEQUENCE_GENERATOR = new AtomicLong(0);

    private final String sourceNodeId;
    private final String destinationNodeId;
    private final long sequenceNumber;
    private final long timestamp;
    private final String payloadType;
    private final byte[] rawDataBuffer;

    /**
     * Constructs a new FibrePacket with an auto-generated sequence number.
     *
     * @param sourceNodeId      identifier of the originating node
     * @param destinationNodeId identifier of the target node (or "broadcast")
     * @param payloadType       discriminator for the payload format in rawDataBuffer
     * @param rawDataBuffer     raw binary payload data
     */
    public FibrePacket(String sourceNodeId,
                       String destinationNodeId,
                       String payloadType,
                       byte[] rawDataBuffer) {
        this.sourceNodeId = Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        this.destinationNodeId = Objects.requireNonNull(destinationNodeId, "destinationNodeId");
        this.sequenceNumber = SEQUENCE_GENERATOR.incrementAndGet();
        this.timestamp = System.currentTimeMillis();
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.rawDataBuffer = rawDataBuffer != null
                ? rawDataBuffer.clone()
                : new byte[0];
    }

    /**
     * Full constructor for testing and deserialisation use cases where the
     * sequence number and timestamp should be explicitly controlled.
     */
    public FibrePacket(String sourceNodeId,
                       String destinationNodeId,
                       long sequenceNumber,
                       long timestamp,
                       String payloadType,
                       byte[] rawDataBuffer) {
        this.sourceNodeId = Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        this.destinationNodeId = Objects.requireNonNull(destinationNodeId, "destinationNodeId");
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.rawDataBuffer = rawDataBuffer != null
                ? rawDataBuffer.clone()
                : new byte[0];
    }

    // ---------------------------------------------------------------
    //  Getters
    // ---------------------------------------------------------------

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public String getDestinationNodeId() {
        return destinationNodeId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getPayloadType() {
        return payloadType;
    }

    /** Returns a defensive copy of the raw data buffer. */
    public byte[] getRawDataBuffer() {
        return rawDataBuffer.clone();
    }

    // ---------------------------------------------------------------
    //  Object overrides
    // ---------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FibrePacket)) return false;
        FibrePacket that = (FibrePacket) o;
        return sequenceNumber == that.sequenceNumber
                && timestamp == that.timestamp
                && sourceNodeId.equals(that.sourceNodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceNodeId, sequenceNumber);
    }

    @Override
    public String toString() {
        return "FibrePacket{" +
                "src='" + sourceNodeId + '\'' +
                ", dst='" + destinationNodeId + '\'' +
                ", seq=" + sequenceNumber +
                ", type='" + payloadType + '\'' +
                ", len=" + rawDataBuffer.length +
                '}';
    }
}
