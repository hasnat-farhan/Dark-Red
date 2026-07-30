package com.antor.sosblue.inbox;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static, thread-safe registry of active conversations shared between
 * {@code ChatActivity} (which writes on every send/receive) and
 * {@code MainActivity} (which reads to display the inbox list).
 *
 * <p>This follows the same pattern as
 * {@link com.antor.sosblue.notification.NotificationHelper}'s static
 * display-name cache — no database, just an in-memory map that lives
 * for the app's process lifetime.</p>
 */
public final class ConversationRegistry {

    private static final String TAG = "ConversationRegistry";

    /**
     * Map: normalized conversation ID (other party's phone) → model.
     * Thread-safe via ConcurrentHashMap.
     */
    private static final ConcurrentHashMap<String, ConversationModel> conversations =
            new ConcurrentHashMap<>();

    private ConversationRegistry() {}

    // ---------------------------------------------------------------
    //  Read
    // ---------------------------------------------------------------

    /**
     * Returns the conversation for the given ID, or {@code null}.
     */
    @Nullable
    public static ConversationModel get(@NonNull String conversationId) {
        return conversations.get(conversationId);
    }

    /**
     * Returns all conversations sorted by last message timestamp (newest first).
     */
    @NonNull
    public static List<ConversationModel> getAll() {
        List<ConversationModel> list = new ArrayList<>(conversations.values());
        Collections.sort(list, (a, b) -> Long.compare(b.getLastTimestamp(), a.getLastTimestamp()));
        return list;
    }

    /**
     * Returns the number of active conversations.
     */
    public static int count() {
        return conversations.size();
    }

    // ---------------------------------------------------------------
    //  Write
    // ---------------------------------------------------------------

    /**
     * Creates or updates a conversation entry.
     *
     * @param conversationId normalized phone of the other party
     * @param displayName    best-known display name
     * @param lastMessage    preview of the message (truncated to 80 chars)
     * @param lastTimestamp  epoch millis
     * @param isOutgoing     {@code true} if local user sent the message
     * @param hasMedia       {@code true} if message contains media
     * @param incrementUnread {@code true} to increment unread counter (for incoming messages)
     * @param transportMode  transport used (e.g. "SOSBLUE_MESH", "F2P_SERVERLESS", "SMS_FALLBACK")
     */
    public static void update(@NonNull String conversationId,
                              @NonNull String displayName,
                              @NonNull String lastMessage,
                              long lastTimestamp,
                              boolean isOutgoing,
                              boolean hasMedia,
                              boolean incrementUnread,
                              @NonNull String transportMode) {
        String preview = lastMessage.length() > 80
                ? lastMessage.substring(0, 80) + "…"
                : lastMessage;

        ConversationModel existing = conversations.get(conversationId);
        if (existing != null) {
            existing.setDisplayName(displayName)
                    .setLastMessage(preview)
                    .setLastTimestamp(lastTimestamp)
                    .setHasMedia(hasMedia)
                    .setOutgoing(isOutgoing)
                    .setLastTransportMode(transportMode);
            if (incrementUnread) {
                existing.incrementUnread();
            } else {
                existing.setUnreadCount(0);  // reset on outgoing messages
            }
        } else {
            ConversationModel model = new ConversationModel(
                    conversationId, displayName, preview, lastTimestamp);
            model.setHasMedia(hasMedia)
                    .setOutgoing(isOutgoing)
                    .setUnreadCount(incrementUnread ? 1 : 0)
                    .setLastTransportMode(transportMode);
            conversations.put(conversationId, model);
        }
        Log.d(TAG, "Conversation updated: " + displayName + " (" + conversationId + ")");
    }

    /**
     * Marks a conversation as read (resets unread count).
     */
    public static void markRead(@NonNull String conversationId) {
        ConversationModel existing = conversations.get(conversationId);
        if (existing != null) {
            existing.setUnreadCount(0);
        }
    }

    /**
     * Removes a conversation from the registry.
     */
    public static void remove(@NonNull String conversationId) {
        conversations.remove(conversationId);
    }

    /**
     * Clears all conversations (e.g. on sign-out).
     */
    public static void clearAll() {
        conversations.clear();
    }
}
