package com.antor.sosblue;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory representation of a single chat message.
 * Supports text, image, and video media content.
 */
public class MessageModel {

    /** Message content type constants. */
    public static final int TYPE_TEXT  = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_VIDEO = 2;

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

    // ── Fix #4: AtomicLong prevents ID collisions when messages are
    //    created on different threads (UI thread + engine callback).
    private static final AtomicLong nextId = new AtomicLong(0);

    /** Text message constructor. */
    public MessageModel(@NonNull String text, boolean isSent) {
        this(text, isSent, null, null, TYPE_TEXT, null, null, 0);
    }

    /** Text message constructor with phone identity. */
    public MessageModel(@NonNull String text, boolean isSent,
                        @Nullable String senderPhone,
                        @Nullable String recipientPhone) {
        this(text, isSent, senderPhone, recipientPhone, TYPE_TEXT, null, null, 0);
    }

    /** Media message constructor (image or video). */
    public MessageModel(@NonNull String text, boolean isSent,
                        @Nullable String senderPhone, @Nullable String recipientPhone,
                        int contentType, @Nullable String mediaUri,
                        @Nullable String mediaMimeType, long mediaSize) {
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

    /** Returns a human-readable size string (e.g. "2.4 MB"). */
    @NonNull
    public String getFormattedSize() {
        if (mediaSize <= 0) return "";
        if (mediaSize < 1024) return mediaSize + " B";
        if (mediaSize < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", mediaSize / 1024.0);
        if (mediaSize < 1024 * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mediaSize / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT, "%.2f GB", mediaSize / (1024.0 * 1024 * 1024));
    }
}
