package com.antor.sosblue.news;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single broadcast news item transmitted over any of the
 * three transport tiers (SOSBlue Mesh, F2P Serverless, SMS).
 *
 * <p>Each news packet carries:</p>
 * <ul>
 *   <li>A unique {@code newsId} for deduplication</li>
 *   <li>{@code authorName} — the sender's display name</li>
 *   <li>{@code authorPhone} — the sender's E.164 phone number</li>
 *   <li>{@code transportType} — which tier delivered it</li>
 *   <li>{@code textPayload} — the broadcast message body</li>
 *   <li>{@code mediaBase64} — optional Base64-encoded media bytes</li>
 *   <li>{@code mediaMimeType} — MIME type of the attached media</li>
 *   <li>{@code timestamp} — Unix epoch millis</li>
 * </ul>
 */
public final class F2PNewsPacket {

    private static final AtomicLong nextLocalId = new AtomicLong(0);

    /** Transport tier used for this news broadcast. */
    public enum TransportType {
        SOSBLUE_MESH("SOSBlue Mesh"),
        F2P_SERVERLESS("F2P"),
        SMS_FALLBACK("SMS");

        private final String label;

        TransportType(String label) { this.label = label; }

        @NonNull
        public String getLabel() { return label; }

        @NonNull
        public static TransportType fromBridgeMode(@NonNull String bridgeName) {
            switch (bridgeName.toUpperCase(java.util.Locale.ROOT)) {
                case "SOSBLUE_MESH": return SOSBLUE_MESH;
                case "F2P_SERVERLESS": return F2P_SERVERLESS;
                case "SMS_FALLBACK": return SMS_FALLBACK;
                default: return SOSBLUE_MESH;
            }
        }
    }

    private final long localId;
    private final String newsId;
    private final String authorName;
    private final String authorPhone;
    private final TransportType transportType;
    private final String textPayload;
    private final String mediaBase64;
    private final String mediaMimeType;
    private final long timestamp;
    private boolean isRead;

    /** Full constructor for incoming news packets. */
    public F2PNewsPacket(@NonNull String newsId,
                         @NonNull String authorName,
                         @NonNull String authorPhone,
                         @NonNull TransportType transportType,
                         @NonNull String textPayload,
                         @Nullable String mediaBase64,
                         @Nullable String mediaMimeType,
                         long timestamp) {
        this.localId = nextLocalId.getAndIncrement();
        this.newsId = Objects.requireNonNull(newsId);
        this.authorName = Objects.requireNonNull(authorName);
        this.authorPhone = Objects.requireNonNull(authorPhone);
        this.transportType = Objects.requireNonNull(transportType);
        this.textPayload = Objects.requireNonNull(textPayload);
        this.mediaBase64 = mediaBase64;
        this.mediaMimeType = mediaMimeType;
        this.timestamp = timestamp;
        this.isRead = false;
    }

    /** Convenience constructor for locally-authored news. */
    public F2PNewsPacket(@NonNull String authorName,
                         @NonNull String authorPhone,
                         @NonNull TransportType transportType,
                         @NonNull String textPayload) {
        this(UUID.randomUUID().toString(), authorName, authorPhone,
                transportType, textPayload, null, null, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------
    //  Getters
    // ---------------------------------------------------------------

    public long getLocalId()             { return localId; }
    @NonNull
    public String getNewsId()            { return newsId; }
    @NonNull
    public String getAuthorName()        { return authorName; }
    @NonNull
    public String getAuthorPhone()       { return authorPhone; }
    @NonNull
    public TransportType getTransportType() { return transportType; }
    @NonNull
    public String getTextPayload()       { return textPayload; }
    @Nullable
    public String getMediaBase64()       { return mediaBase64; }
    @Nullable
    public String getMediaMimeType()     { return mediaMimeType; }
    public long getTimestamp()           { return timestamp; }
    public boolean isRead()              { return isRead; }
    public void setRead(boolean read)    { this.isRead = read; }

    public boolean hasMedia() {
        return mediaBase64 != null && !mediaBase64.isEmpty();
    }

    /** Returns a human-friendly transport badge label. */
    @NonNull
    public String getTransportLabel() {
        return transportType.getLabel();
    }

    // ---------------------------------------------------------------
    //  SMS wire format: "[NEWS:AuthorName] Your news text here"
    // ---------------------------------------------------------------

    /**
     * Encodes this packet as an SMS-friendly text string.
     * Format: {@code [NEWS:AuthorName] textPayload}
     */
    @NonNull
    public String toSmsText() {
        return "[NEWS:" + authorName + "] " + textPayload;
    }

    /**
     * Attempts to parse a string into a skeleton {@link F2PNewsPacket}
     * for SMS-originated news. Returns null if the format does not match.
     *
     * @param raw       the raw SMS body
     * @param senderPhone the sender's phone number
     * @param transport  the transport type (expected SMS_FALLBACK)
     */
    @Nullable
    public static F2PNewsPacket fromSmsText(@NonNull String raw,
                                             @NonNull String senderPhone,
                                             @NonNull TransportType transport) {
        if (raw.startsWith("[NEWS:") && raw.contains("] ")) {
            int closeBracket = raw.indexOf("] ");
            String namePart = raw.substring(6, closeBracket);
            String textPart = raw.substring(closeBracket + 2);
            return new F2PNewsPacket(
                    UUID.randomUUID().toString(),
                    namePart,
                    senderPhone,
                    transport,
                    textPart,
                    null, null,
                    System.currentTimeMillis()
            );
        }
        return null;
    }

    // ---------------------------------------------------------------
    //  Object overrides
    // ---------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof F2PNewsPacket)) return false;
        F2PNewsPacket that = (F2PNewsPacket) o;
        return newsId.equals(that.newsId);
    }

    @Override
    public int hashCode() {
        return newsId.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return "F2PNewsPacket{" +
                "author='" + authorName + '\'' +
                ", transport=" + transportType +
                ", text='" + textPayload.substring(0, Math.min(40, textPayload.length())) + '\'' +
                ", ts=" + timestamp +
                '}';
    }
}
