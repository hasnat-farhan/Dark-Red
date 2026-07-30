package com.antor.sosblue.inbox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Lightweight model representing a single conversation / chat thread
 * in the inbox (conversation list) shown on the main screen.
 *
 * <p>Instances are held in a static registry ({@link ConversationRegistry})
 * that is updated by {@code ChatActivity} on every send and receive.</p>
 */
public class ConversationModel {

    private final String conversationId;   // normalized phone of the other party
    private String displayName;            // best-known display name
    private String lastMessage;            // preview of the most recent message
    private long lastTimestamp;             // epoch millis of the last message
    private int unreadCount;               // messages since the user last viewed
    private boolean hasMedia;              // true if last message contains media
    private boolean isOutgoing;            // true if the last message was sent by local user

    public ConversationModel(@NonNull String conversationId,
                             @NonNull String displayName,
                             @NonNull String lastMessage,
                             long lastTimestamp) {
        this.conversationId = conversationId;
        this.displayName = displayName;
        this.lastMessage = lastMessage;
        this.lastTimestamp = lastTimestamp;
        this.unreadCount = 0;
        this.hasMedia = false;
        this.isOutgoing = false;
    }

    // ── Getters ──────────────────────────────────────────────────

    @NonNull
    public String getConversationId() { return conversationId; }

    @NonNull
    public String getDisplayName()    { return displayName; }

    @NonNull
    public String getLastMessage()    { return lastMessage; }

    public long getLastTimestamp()    { return lastTimestamp; }

    public int getUnreadCount()       { return unreadCount; }

    public boolean hasMedia()         { return hasMedia; }

    public boolean isOutgoing()       { return isOutgoing; }

    // ── Setters (fluent for convenience) ──────────────────────────

    public ConversationModel setDisplayName(@NonNull String displayName) {
        this.displayName = displayName;
        return this;
    }

    public ConversationModel setLastMessage(@NonNull String lastMessage) {
        this.lastMessage = lastMessage;
        return this;
    }

    public ConversationModel setLastTimestamp(long lastTimestamp) {
        this.lastTimestamp = lastTimestamp;
        return this;
    }

    public ConversationModel setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
        return this;
    }

    public ConversationModel incrementUnread() {
        this.unreadCount++;
        return this;
    }

    public ConversationModel setHasMedia(boolean hasMedia) {
        this.hasMedia = hasMedia;
        return this;
    }

    public ConversationModel setOutgoing(boolean outgoing) {
        isOutgoing = outgoing;
        return this;
    }

    /** Returns a single-character avatar (first letter of display name). */
    public char getAvatarChar() {
        char c = displayName.charAt(0);
        return Character.isLetterOrDigit(c) ? c : '#';
    }

    /** Formats a relative time string like "2m", "1h", "3d". */
    @NonNull
    public String getRelativeTime() {
        long diff = System.currentTimeMillis() - lastTimestamp;
        if (diff < 60_000) return "now";
        if (diff < 3_600_000) return (diff / 60_000) + "m";
        if (diff < 86_400_000) return (diff / 3_600_000) + "h";
        if (diff < 604_800_000) return (diff / 86_400_000) + "d";
        return (diff / 604_800_000) + "w";
    }
}
