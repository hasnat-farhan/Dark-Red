package com.antor.sosblue.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * End-to-end encrypted message envelope for the F2P mesh network.
 *
 * <p>Each message is wrapped in an envelope that contains:</p>
 * <ul>
 *   <li>Sender phone number (source identifier)</li>
 *   <li>Recipient phone number (target identifier / routing key)</li>
 *   <li>Encrypted payload (AES-256-GCM ciphertext)</li>
 *   <li>Timestamp</li>
 *   <li>Nonce (unique per-message, prevents replay)</li>
 * </ul>
 *
 * <p><strong>Wire format:</strong><br>
 * {@code [senderPhoneLen:4][senderPhone][recipientPhoneLen:4][recipientPhone]
 *  [nonceLen:4][nonce][timestamp:8][encryptedPayloadLen:4][encryptedPayload]}</p>
 */
public final class F2PMessage {

    private final String senderPhone;
    private final String recipientPhone;
    private final byte[] encryptedPayload;
    private final long timestamp;
    private final byte[] nonce;

    public F2PMessage(String senderPhone,
                      String recipientPhone,
                      byte[] encryptedPayload,
                      long timestamp,
                      byte[] nonce) {
        this.senderPhone = Objects.requireNonNull(senderPhone, "senderPhone");
        this.recipientPhone = Objects.requireNonNull(recipientPhone, "recipientPhone");
        this.encryptedPayload = encryptedPayload != null ? encryptedPayload.clone() : new byte[0];
        this.timestamp = timestamp;
        this.nonce = nonce != null ? nonce.clone() : new byte[0];
    }

    // ---------------------------------------------------------------
    //  Getters
    // ---------------------------------------------------------------

    public String getSenderPhone()      { return senderPhone; }
    public String getRecipientPhone()   { return recipientPhone; }
    public byte[] getEncryptedPayload() { return encryptedPayload.clone(); }
    public long getTimestamp()          { return timestamp; }
    public byte[] getNonce()            { return nonce.clone(); }

    // ---------------------------------------------------------------
    //  Serialization
    // ---------------------------------------------------------------

    /**
     * Serializes this message to a byte array for transmission.
     */
    public byte[] serialize() {
        byte[] senderBytes = senderPhone.getBytes(StandardCharsets.UTF_8);
        byte[] recipientBytes = recipientPhone.getBytes(StandardCharsets.UTF_8);

        int totalSize = 4 + senderBytes.length
                + 4 + recipientBytes.length
                + 4 + nonce.length
                + 8
                + 4 + encryptedPayload.length;

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.putInt(senderBytes.length);
        buf.put(senderBytes);
        buf.putInt(recipientBytes.length);
        buf.put(recipientBytes);
        buf.putInt(nonce.length);
        buf.put(nonce);
        buf.putLong(timestamp);
        buf.putInt(encryptedPayload.length);
        buf.put(encryptedPayload);
        return buf.array();
    }

    /**
     * Deserializes a byte array back into an F2PMessage.
     *
     * @throws IllegalArgumentException if the data is malformed
     */
    public static F2PMessage deserialize(byte[] data) {
        if (data == null || data.length < 20) {
            throw new IllegalArgumentException("Data too short to be a valid F2PMessage");
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(data);

            int senderLen = buf.getInt();
            byte[] senderBytes = new byte[senderLen];
            buf.get(senderBytes);

            int recipientLen = buf.getInt();
            byte[] recipientBytes = new byte[recipientLen];
            buf.get(recipientBytes);

            int nonceLen = buf.getInt();
            byte[] nonceBytes = new byte[nonceLen];
            buf.get(nonceBytes);

            long timestamp = buf.getLong();

            int payloadLen = buf.getInt();
            byte[] payloadBytes = new byte[payloadLen];
            buf.get(payloadBytes);

            return new F2PMessage(
                    new String(senderBytes, StandardCharsets.UTF_8),
                    new String(recipientBytes, StandardCharsets.UTF_8),
                    payloadBytes,
                    timestamp,
                    nonceBytes
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize F2PMessage", e);
        }
    }

    // ---------------------------------------------------------------
    //  Object overrides
    // ---------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof F2PMessage)) return false;
        F2PMessage that = (F2PMessage) o;
        return timestamp == that.timestamp
                && senderPhone.equals(that.senderPhone)
                && recipientPhone.equals(that.recipientPhone)
                && Arrays.equals(nonce, that.nonce);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(senderPhone, recipientPhone, timestamp);
        result = 31 * result + Arrays.hashCode(nonce);
        return result;
    }

    @Override
    public String toString() {
        return "F2PMessage{" +
                "from='" + senderPhone + '\'' +
                ", to='" + recipientPhone + '\'' +
                ", ts=" + timestamp +
                ", payloadLen=" + encryptedPayload.length +
                '}';
    }
}
