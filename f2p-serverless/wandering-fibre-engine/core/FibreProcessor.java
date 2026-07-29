package com.antor.f2p.engine.core;

import com.antor.f2p.engine.api.FibrePacket;
import com.antor.f2p.engine.api.FibreSignal;
import com.antor.f2p.engine.api.WanderingFibreEngine;
import com.antor.f2p.engine.network.RoutingTable;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Enhanced processor that handles signals, packets, encryption/decryption,
 * and dynamic routing via {@link FibrePathRouter}.
 * <p>
 * Outbound signals are encrypted before being forwarded. Inbound packets are
 * decrypted before local delivery.
 * </p>
 */
public class FibreProcessor {

    private static final Logger LOG = Logger.getLogger(FibreProcessor.class.getName());

    /** Reserved payload type for ACK messages. */
    private static final String ACK_TYPE = "_ack";
    /** Reserved payload type for NACK messages. */
    private static final String NACK_TYPE = "_nack";

    private final WanderingFibreEngine engine;

    FibreProcessor(WanderingFibreEngine engine) {
        this.engine = engine;
    }

    public void process(EngineLoop.WorkUnit work) {
        if (work.isSignal()) {
            processSignal(work.getSignal());
        } else if (work.isPacket()) {
            processInboundPacket(work.getPacket());
        }
    }

    // ---------------------------------------------------------------
    //  Signal handling
    // ---------------------------------------------------------------

    private void processSignal(FibreSignal signal) {
        LOG.fine("Processing signal: " + signal);
        engine.notifySignal(signal);

        String sourceNodeId = engine.getLocalNodeId();
        String destNodeId = resolveDestination(signal);
        byte[] payloadBytes = serialisePayload(signal);

        // Encrypt the payload if we have a session key with the destination
        FibreSecurityHandler security = engine.getSecurityHandler();
        byte[] dataBuffer;
        if (destNodeId.equals("broadcast")) {
            dataBuffer = payloadBytes;
        } else if (security.hasSessionWith(destNodeId)) {
            dataBuffer = security.encrypt(destNodeId, payloadBytes);
            LOG.fine("Payload encrypted for peer: " + destNodeId);
        } else {
            LOG.fine("No session key for " + destNodeId + " — sending unencrypted");
            dataBuffer = payloadBytes;
        }

        FibrePacket outboundPacket = new FibrePacket(
                sourceNodeId, destNodeId,
                signal.getType(), dataBuffer);

        LOG.fine("Signal converted to packet: " + outboundPacket);

        // Always deliver locally — the signal originated here
        engine.notifyPacket(outboundPacket);

        // Route via the routing table (for mesh forwarding to other nodes)
        if (destNodeId.equals("broadcast")) {
            engine.getRoutingTable().recordOutboundPacket(outboundPacket);
        } else {
            routePacket(outboundPacket); // handles recordOutboundPacket on success
        }
    }

    // ---------------------------------------------------------------
    //  Inbound packet handling
    // ---------------------------------------------------------------

    private void processInboundPacket(FibrePacket packet) {
        LOG.fine("Processing inbound packet: " + packet);

        // Handle control packets (ACK / NACK)
        if (packet.getPayloadType().equals(ACK_TYPE)) {
            handleAck(packet);
            return;
        }
        if (packet.getPayloadType().equals(NACK_TYPE)) {
            handleNack(packet);
            return;
        }

        // Decrypt payload if needed
        byte[] decrypted = tryDecrypt(packet);

        // Check local delivery
        if (packet.getDestinationNodeId().equals(engine.getLocalNodeId())
                || packet.getDestinationNodeId().equals("broadcast")) {
            engine.getRoutingTable().recordInboundPacket(packet);
            // Reconstruct a FibrePacket with decrypted data for listeners
            FibrePacket deliverPacket = decrypted != null
                    ? new FibrePacket(packet.getSourceNodeId(), packet.getDestinationNodeId(),
                    packet.getSequenceNumber(), packet.getTimestamp(),
                    packet.getPayloadType(), decrypted)
                    : packet;
            engine.notifyPacket(deliverPacket);
            return;
        }

        // Forward via dynamic routing
        engine.getRoutingTable().recordForwardedPacket(packet);
        routePacket(packet);
    }

    // ---------------------------------------------------------------
    //  Routing
    // ---------------------------------------------------------------

    private void routePacket(FibrePacket packet) {
        RoutingTable routingTable = engine.getRoutingTable();
        Optional<String> nextHop = routingTable.resolveNextHop(packet.getDestinationNodeId());
        if (nextHop.isPresent()) {
            LOG.fine("Routing packet " + packet.getSequenceNumber()
                    + " to " + packet.getDestinationNodeId()
                    + " via " + nextHop.get());
            routingTable.recordOutboundPacket(packet); // counts + registers ACK
            // Future: actual network send via nextHop.get()
        } else {
            LOG.warning("No route to " + packet.getDestinationNodeId()
                    + " — dropping packet " + packet.getSequenceNumber());
            routingTable.recordDroppedPacket(packet);
        }
    }

    // ---------------------------------------------------------------
    //  ACK / NACK
    // ---------------------------------------------------------------

    private void handleAck(FibrePacket packet) {
        long seq = extractSequence(packet);
        if (seq >= 0) {
            engine.getRoutingTable().getPathRouter().processAck(seq);
        }
    }

    private void handleNack(FibrePacket packet) {
        long seq = extractSequence(packet);
        if (seq >= 0) {
            engine.getRoutingTable().getPathRouter().processNack(seq);
        }
    }

    private long extractSequence(FibrePacket packet) {
        try {
            return Long.parseLong(new String(packet.getRawDataBuffer(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            LOG.warning("Malformed ACK/NACK packet: " + packet);
            return -1;
        }
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private byte[] tryDecrypt(FibrePacket packet) {
        FibreSecurityHandler security = engine.getSecurityHandler();
        String src = packet.getSourceNodeId();
        if (security.hasSessionWith(src)) {
            try {
                return security.decrypt(src, packet.getRawDataBuffer());
            } catch (Exception e) {
                LOG.warning("Decryption failed for packet from " + src
                        + " seq=" + packet.getSequenceNumber());
            }
        }
        return null;
    }

    private String resolveDestination(FibreSignal signal) {
        Object dest = signal.getPayload().get("_destination");
        return dest != null ? dest.toString() : "broadcast";
    }

    private byte[] serialisePayload(FibreSignal signal) {
        StringBuilder sb = new StringBuilder("{");
        signal.getPayload().forEach((k, v) ->
                sb.append('"').append(k).append("\":\"").append(v).append("\","));
        if (sb.length() > 1) sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
