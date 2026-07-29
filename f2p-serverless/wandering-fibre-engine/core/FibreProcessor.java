package com.antor.f2p.engine.core;

import com.antor.f2p.engine.api.FibrePacket;
import com.antor.f2p.engine.api.FibreSignal;
import com.antor.f2p.engine.api.WanderingFibreEngine;
import com.antor.f2p.engine.network.RoutingTable;

import java.nio.charset.StandardCharsets;
import java.util.Map;
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
        // ── Skip ECDH encryption for F2P phone-addressed messages ──────
        // F2P messages carry their own phone-derived encryption in the
        // f2p_envelope field, so we skip the ECDH layer to avoid double
        // encryption that would prevent the recipient from decrypting.
        FibreSecurityHandler security = engine.getSecurityHandler();
        byte[] dataBuffer;
        boolean isF2pTargeted = payloadStrContains(payloadBytes, "recipient_phone");
        if (destNodeId.equals("broadcast")) {
            dataBuffer = payloadBytes;
        } else if (isF2pTargeted) {
            // F2P messages are already encrypted with phone-derived key
            dataBuffer = payloadBytes;
            LOG.fine("F2P targeted message — skipping ECDH layer");
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

        // ── Message deduplication ─────────────────────────────────
        // Check if this message has already been processed via
        // another path (e.g., direct UDP + multi-hop relay).
        String payloadStr = new String(packet.getRawDataBuffer(),
                java.nio.charset.StandardCharsets.UTF_8);
        String messageId = extractJsonField(payloadStr, "message_id");
        if (messageId != null) {
            MessageDeduplicator dedup = engine.getMessageDeduplicator();
            if (!dedup.trySeen(messageId)) {
                LOG.fine("Duplicate message " + messageId
                        + " (seq=" + packet.getSequenceNumber()
                        + ") — dropping");
                engine.getRoutingTable().recordDroppedPacket(packet);
                return;
            }
        }

        // Handle control packets (ACK / NACK)
        if (packet.getPayloadType().equals(ACK_TYPE)) {
            handleAck(packet);
            return;
        }
        if (packet.getPayloadType().equals(NACK_TYPE)) {
            handleNack(packet);
            return;
        }

        // ── F2P Identity Routing Filter ──────────────────────────
        // Inspect the payload for F2P envelope metadata.
        // If a recipient_phone is present, only deliver to the matching node.
        // If this node's phone number does NOT match the recipient, drop it
        // (intermediate nodes must not be able to read the encrypted payload).
        String recipientPhone = extractJsonField(payloadStr, "recipient_phone");

        if (recipientPhone != null) {
            // ── Normalize both phone numbers before comparing ────────
            // This ensures that formatting differences (spaces, hyphens,
            // leading zeros, etc.) do not cause false mismatches between
            // devices on the same local network.
            String normalizedRecipient = normalizePhoneNumber(recipientPhone);
            String localPhone = engine.getLocalNodeId();
            String normalizedLocal = normalizePhoneNumber(localPhone);

            if (!normalizedRecipient.equals(normalizedLocal)) {
                // ── Multi-hop TTL Relay ───────────────────────────────
                // Extract TTL from the payload. If TTL > 0, decrement
                // and re-broadcast via the routing table. If TTL is 0
                // or absent, the packet expires at this node.
                String ttlStr = extractJsonField(payloadStr, "ttl");
                int ttl = 0;
                if (ttlStr != null) {
                    try {
                        ttl = Integer.parseInt(ttlStr.trim());
                    } catch (NumberFormatException ignored) {}
                }

                if (ttl > 0) {
                    int newTtl = ttl - 1;
                    // Rebuild the payload with decremented TTL
                    String updatedPayload = payloadStr.replaceFirst(
                            "\"ttl\"\\s*:\\s*\"" + ttl + "\"",
                            "\"ttl\":\"" + newTtl + "\"");
                    FibrePacket relayPacket = new FibrePacket(
                            packet.getSourceNodeId(),
                            packet.getDestinationNodeId(),
                            packet.getSequenceNumber(),
                            packet.getTimestamp(),
                            packet.getPayloadType(),
                            updatedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                    LOG.fine("Relaying packet " + packet.getSequenceNumber()
                            + " to " + recipientPhone
                            + " [norm=" + normalizedRecipient + "]"
                            + " — TTL=" + ttl + "→" + newTtl
                            + ", this node=" + localPhone
                            + " [norm=" + normalizedLocal + "]");

                    engine.getRoutingTable().recordForwardedPacket(relayPacket);
                    routePacket(relayPacket);
                    return;
                }

                LOG.fine("Packet addressed to " + recipientPhone
                        + " [norm=" + normalizedRecipient + "]"
                        + " — not for this node (" + localPhone
                        + " [norm=" + normalizedLocal + "])"
                        + " — TTL expired, dropping");
                engine.getRoutingTable().recordDroppedPacket(packet);
                return;
            }
            LOG.fine("F2P packet addressed to this node — delivering");
        }

        // Skip ECDH decryption for F2P targeted messages — they carry
        // their own phone-derived encryption in the f2p_envelope field.
        boolean isF2pTargeted = recipientPhone != null;

        // Decrypt payload if needed (skip for F2P targeted messages)
        byte[] decrypted = isF2pTargeted ? null : tryDecrypt(packet);
        if (decrypted != null) {
            LOG.fine("Decryption succeeded for packet from " + packet.getSourceNodeId()
                    + " seq=" + packet.getSequenceNumber());
        } else if (!isF2pTargeted) {
            LOG.fine("Decryption skipped or failed for packet from " + packet.getSourceNodeId()
                    + " seq=" + packet.getSequenceNumber());
        }

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

    /**
     * Extracts a JSON string field value from a simple key-value payload.
     * Handles both quoted and unquoted values.
     */
    private String extractJsonField(String json, String key) {
        try {
            String searchKey = "\"" + key + "\"";
            int keyIdx = json.indexOf(searchKey);
            if (keyIdx < 0) {
                // Try unquoted key
                searchKey = key;
                keyIdx = json.indexOf(searchKey);
            }
            if (keyIdx < 0) return null;
            int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
            if (colonIdx < 0) return null;
            int valueStart = json.indexOf('"', colonIdx + 1);
            if (valueStart < 0) {
                // Unquoted value — read until comma or end
                int valueEnd = json.indexOf(',', colonIdx + 1);
                if (valueEnd < 0) valueEnd = json.indexOf('}', colonIdx + 1);
                if (valueEnd < 0) valueEnd = json.length();
                return json.substring(colonIdx + 1, valueEnd).trim();
            }
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd < 0) return null;
            return json.substring(valueStart + 1, valueEnd);
        } catch (Exception e) {
            LOG.fine("Failed to extract field '" + key + "' from payload");
            return null;
        }
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
        boolean first = true;
        for (Map.Entry<String, Object> entry : signal.getPayload().entrySet()) {
            if (!first) sb.append(',');
            sb.append('\"').append(entry.getKey()).append("\":\"")
              .append(String.valueOf(entry.getValue())).append('\"');
            first = false;
        }
        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Normalises a phone number so that different formatting does not
     * cause false mismatches during recipient-address matching.
     * Strips all non-digit characters EXCEPT a leading '+'.
     */
    private static String normalizePhoneNumber(String phone) {
        if (phone == null) return "";
        boolean hasPlus = phone.startsWith("+");
        String digitsOnly = phone.replaceAll("[^\\d]", "");
        return hasPlus ? "+" + digitsOnly : digitsOnly;
    }

    /**
     * Quick check if a byte payload contains a given key string.
     */
    private static boolean payloadStrContains(byte[] data, String key) {
        if (data == null) return false;
        String s = new String(data, StandardCharsets.UTF_8);
        return s.contains(key);
    }
}
