package com.antor.sosblue;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory representation of a single chat message.
 * Supports text, image, and video media content, plus a per-message
 * transport channel (mesh / F2P / SMS) and a delivery lifecycle
 * (SENDING / SENT / DELIVERED / FAILED).
 */
public class MessageModel {

    /** Message content type constants. */
    public static final int TYPE_TEXT  = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_VIDEO = 2;

    /** Transport channel constants — set per message at creation time. */
    public static final int TRANSPORT_UNKNOWN = 0;
    public static final int TRANSPORT_MESH    = 1;
    public static final int TRANSPORT_F2P     = 2;
    public static final int TRANSPORT_SMS     = 3;

    /** Delivery lifecycle constants — see {@link #setStatus(int)} */
    public static final int STATUS_SENDING   = 0; // local optimistic placeholder
    public static final int STATUS_SENT      = 1; // handed off to transport
    public static final int STATUS_DELIVERED = 2; // recipient confirmed (mesh ACK, SMS sent-confirm)
    public static final int STATUS_FAILED    = 3; // bridge error / non-recoverable

    private final long id;
    private final String text;
    private final long timestamp;
    private final boolean isSent;
    private final String senderPhone;
    private final String recipientPhone;
    private final int contentType;
    private final String mediaUri;
    private final String mediaMimeType;
    private final long mediaSize;

    /** Channel that carried (or will carry) this message. Immutable. */
    private final int transport;

    /** Mutable status — set when bridge reports sent/delivered/failed. */
    private int status;

    // ── Fix #4: AtomicLong prevents ID collisions when messages are
    //    created on different threads (UI thread + engine callback).
    private static final AtomicLong nextId = new AtomicLong(0);

    /** Text message constructor (legacy). Defaults: SENT, transport UNKNOWN. */
    public MessageModel(@NonNull String text, boolean isSent) {
        this(text, isSent, null, null, TYPE_TEXT, null, null, 0,
                TRANSPORT_UNKNOWN, STATUS_SENT);
    }

    /** Text message constructor with phone identity. */
    public MessageModel(@NonNull String text, boolean isSent,
                        @Nullable String senderPhone,
                        @Nullable String recipientPhone) {
        this(text, isSent, senderPhone, recipientPhone, TYPE_TEXT, null, null, 0,
                TRANSPORT_UNKNOWN, STATUS_SENT);
    }

    /** Text message with explicit transport + status (used by the bridge). */
    public MessageModel(@NonNull String text, boolean isSent,
                        @Nullable String senderPhone,
                        @Nullable String recipientPhone,
                        int transport, int status) {
        this(text, isSent, senderPhone, recipientPhone, TYPE_TEXT, null, null, 0,
                transport, status);
    }

    /** Media message constructor (image or video). */
    public MessageModel(@NonNull String text, boolean isSent,
                        @Nullable String senderPhone, @Nullable String recipientPhone,
                        int contentType, @Nullable String mediaUri,
                        @Nullable String mediaMimeType, long mediaSize) {
        this(text, isSent, senderPhone, recipientPhone, contentType,
                mediaUri, mediaMimeType, mediaSize,
                TRANSPORT_UNKNOWN, STATUS_SENT);
    }

    /** Full constructor — all known fields including transport + status. */
    public MessageModel(@NonNull String text, boolean isSent,
                        @Nullable String senderPhone, @Nullable String recipientPhone,
                        int contentType, @Nullable String mediaUri,
                        @Nullable String mediaMimeType, long mediaSize,
                        int transport, int status) {
        this.id = nextId.getAndIncrement();
        this.text = text;
        this.timestamp = System.currentTimeMillis();
        this.isSent = isSent;
        this.senderPhone = senderPhone;
        this.recipientPhone = recipientPhone;
        this.contentType = contentType;
        this.mediaUri = mediaUri;
        this.mediaMimeType = mediaMimeType;
        this.mediaSize = mediaSize;
        this.transport = transport;
        this.status = status;
    }

    /**
     * Internal constructor used by {@link #fromJson} to reuse the original
     * id + timestamp so DiffUtil keys and ordering stay stable across
     * app restarts.  package-private.
     */
    MessageModel(long id, @NonNull String text, long timestamp, boolean isSent,
                 @Nullable String senderPhone, @Nullable String recipientPhone,
                 int contentType, @Nullable String mediaUri,
                 @Nullable String mediaMimeType, long mediaSize,
                 int transport, int status) {
        this.id = id;
        this.text = text;
        this.timestamp = timestamp;
        this.isSent = isSent;
        this.senderPhone = senderPhone;
        this.recipientPhone = recipientPhone;
        this.contentType = contentType;
        this.mediaUri = mediaUri;
        this.mediaMimeType = mediaMimeType;
        this.mediaSize = mediaSize;
        this.transport = transport;
        this.status = status;
    }

    // ── Getters ──────────────────────────────────────────────────

    public long getId()             { return id; }
    @NonNull
    public String getText()         { return text; }
    public long getTimestamp()      { return timestamp; }
    public boolean isSent()         { return isSent; }
    @Nullable
    public String getSenderPhone()    { return senderPhone; }
    @Nullable
    public String getRecipientPhone() { return recipientPhone; }
    public int getContentType()       { return contentType; }
    public boolean isMedia()          { return contentType != TYPE_TEXT; }
    public boolean isImage()          { return contentType == TYPE_IMAGE; }
    public boolean isVideo()          { return contentType == TYPE_VIDEO; }
    @Nullable
    public String getMediaUri()        { return mediaUri; }
    @Nullable
    public String getMediaMimeType()   { return mediaMimeType; }
    public long getMediaSize()         { return mediaSize; }
    public int getTransport()          { return transport; }
    public int getStatus()             { return status; }

    /** Returns a human-readable size string (e.g. "2.4 MB"). */
    @NonNull
    public String getFormattedSize() {
        if (mediaSize <= 0) return "";
        if (mediaSize < 1024) return mediaSize + " B";
if (mediaSize < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", mediaSize / 1024.0);
        if (mediaSize < 1024 * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mediaSize / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT, "%.2f GB", mediaSize / (1024.0 * 1024 * 1024));
    }

    /**
     * Mutates the delivery status (SENDING → SENT / DELIVERED / FAILED).
     * Called by {@code ChatActivity} from the {@code F2PBridge.OnMessageSendListener}
     * callback once the transport reports back.
     */
    public void setStatus(int newStatus) {
        this.status = newStatus;
    }

    /** Map transport int → drawable resource id (0 means no icon). */
    public static int transportIconRes(int transport) {
        switch (transport) {
            case TRANSPORT_MESH: return R.drawable.ic_transport_mesh;
            case TRANSPORT_F2P:  return R.drawable.ic_transport_f2p;
            case TRANSPORT_SMS:  return R.drawable.ic_sms;
            default:             return 0;
        }
    }

    /** Map transport int → short label string for accessibility / debug. */
    @NonNull
    public static String transportLabel(int transport) {
        switch (transport) {
            case TRANSPORT_MESH: return "Mesh";
            case TRANSPORT_F2P:  return "F2P";
            case TRANSPORT_SMS:  return "SMS";
            default:             return "";
        }
    }

    /** Short status glyph appended after the timestamp (sent only). */
    @NonNull
    public String statusSuffix() {
        if (!isSent) return ""; // incoming messages are already delivered
        switch (status) {
            case STATUS_SENDING:   return "  …";
            case STATUS_SENT:      return "  ✓";
            case STATUS_DELIVERED: return "  ✓✓";
            case STATUS_FAILED:    return "  !";
            default:               return "";
        }
    }

    // ── JSON (de)serialisation for ChatHistoryStore ──────────────

    /** Serialise to a JSON object. */
    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("text", text);
        o.put("ts", timestamp);
        o.put("sent", isSent);
        o.put("sender", senderPhone != null ? senderPhone : JSONObject.NULL);
        o.put("recipient", recipientPhone != null ? recipientPhone : JSONObject.NULL);
        o.put("type", contentType);
        o.put("mediaUri", mediaUri != null ? mediaUri : JSONObject.NULL);
        o.put("mediaMime", mediaMimeType != null ? mediaMimeType : JSONObject.NULL);
        o.put("mediaSize", mediaSize);
        o.put("transport", transport);
        o.put("status", status);
        return o;
    }

    /** Build from a JSON object produced by {@link #toJson()}. */
    @NonNull
    public static MessageModel fromJson(@NonNull JSONObject o) throws JSONException {
        long id = o.optLong("id", nextId.getAndIncrement());
        String text = o.optString("text", "");
        long ts = o.optLong("ts", System.currentTimeMillis());
        boolean sent = o.optBoolean("sent", true);
        String sender = o.isNull("sender") ? null : o.optString("sender", null);
        String recipient = o.isNull("recipient") ? null : o.optString("recipient", null);
        int type = o.optInt("type", TYPE_TEXT);
        String mediaUri = o.isNull("mediaUri") ? null : o.optString("mediaUri", null);
        String mediaMime = o.isNull("mediaMime") ? null : o.optString("mediaMime", null);
        long mediaSize = o.optLong("mediaSize", 0);
        int transport = o.optInt("transport", TRANSPORT_UNKNOWN);
        int status = o.optInt("status", STATUS_SENT);
        return new MessageModel(id, text, ts, sent, sender, recipient,
                type, mediaUri, mediaMime, mediaSize, transport, status);
    }
}
    }
}
