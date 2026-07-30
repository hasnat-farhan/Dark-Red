package com.antor.sosblue.notification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;

import com.antor.sosblue.ChatActivity;
import com.antor.sosblue.R;
import com.antor.sosblue.news.NewsFeedActivity;
import com.antor.sosblue.settings.SettingsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Android notification channels and posts native notifications
 * for incoming direct messages and news broadcasts.
 *
 * <p>Two channels are created on API 26+:</p>
 * <ul>
 *   <li>{@code chat_messages} — High-priority for direct F2P messages</li>
 *   <li>{@code news_broadcasts} — High-priority for broadcast news items</li>
 * </ul>
 *
 * <p>Each notification carries a deep-link {@link PendingIntent} that opens
 * the exact {@link ChatActivity} or {@link NewsFeedActivity} when tapped.</p>
 *
 * <p>A static phone-to-display-name cache is maintained so that notifications
 * show the sender's human-readable username when available (populated by
 * peer discovery heartbeats) instead of a raw phone number.</p>
 */
public final class NotificationHelper {

    private static final String TAG = "NotificationHelper";

    // Channel IDs
    public static final String CHANNEL_CHAT = "chat_messages";
    public static final String CHANNEL_NEWS = "news_broadcasts";

    // Channel names (user-visible)
    private static final String CHANNEL_CHAT_NAME = "Direct Messages";
    private static final String CHANNEL_NEWS_NAME = "News Broadcasts";

    // Channel descriptions
    private static final String CHANNEL_CHAT_DESC =
            "Notifications for incoming peer-to-peer messages via SOSBlue Mesh, F2P, or SMS";
    private static final String CHANNEL_NEWS_DESC =
            "Notifications for broadcast news items pushed over any transport tier";

    // Notification IDs
    private static int newsNotificationId = 2001;

    /**
     * Static phone-to-display-name cache populated by peer discovery heartbeats.
     * Key: normalized phone number (E.164). Value: display name.
     */
    private static final ConcurrentHashMap<String, String> displayNameCache = new ConcurrentHashMap<>();

    /**
     * Per-sender message history for MessagingStyle conversation stacking.
     * Key: sender phone. Value: list of (text, timestamp) entries.
     * Thread-safe via CopyOnWriteArrayList.
     */
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<MessageEntry>>
            messageHistory = new ConcurrentHashMap<>();

    /**
     * A single message entry in a 1:1 conversation history.
     */
    private static class MessageEntry {
        final CharSequence text;
        final long timestamp;

        MessageEntry(CharSequence text, long timestamp) {
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    /**
     * A message entry in a <em>group</em> conversation history.
     * Carries the sender's identity so each message can be attributed
     * to a different {@link Person} in the MessagingStyle.
     */
    private static final class GroupMessageEntry extends MessageEntry {
        final String senderPhone;
        final String senderName;

        GroupMessageEntry(CharSequence text, long timestamp,
                          @NonNull String senderPhone,
                          @NonNull String senderName) {
            super(text, timestamp);
            this.senderPhone = senderPhone;
            this.senderName = senderName;
        }
    }

    /**
     * Per-group message history for multi-sender MessagingStyle stacking.
     * Key: group conversation ID. Value: list of attributed entries.
     */
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<GroupMessageEntry>>
            groupMessageHistory = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------
    //  SharedPreferences persistence
    // ---------------------------------------------------------------

    private static final String PREFS_NAME = "notification_history";
    private static final String KEY_CHAT_HISTORY = "chat_msg_history";
    private static final String KEY_GROUP_HISTORY = "group_msg_history";

    /** SharedPreferences handle — initialised lazily by the constructor. */
    private static android.content.SharedPreferences prefs;

    private static void initPrefs(@NonNull Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadHistories();
    }

    /** Restores both message histories from persisted JSON. */
    private static void loadHistories() {
        if (prefs == null) return;
        // ── 1:1 chat history ────────────────────────────────────────
        String chatJson = prefs.getString(KEY_CHAT_HISTORY, null);
        if (chatJson != null) {
            try {
                JSONObject root = new JSONObject(chatJson);
                java.util.Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONArray arr = root.getJSONArray(key);
                    CopyOnWriteArrayList<MessageEntry> list =
                            new CopyOnWriteArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject e = arr.getJSONObject(i);
                        list.add(new MessageEntry(
                                e.optString("text", ""),
                                e.optLong("timestamp", 0L)));
                    }
                    if (!list.isEmpty()) {
                        messageHistory.put(key, list);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load chat history", e);
            }
        }
        // ── Group chat history ──────────────────────────────────────
        String groupJson = prefs.getString(KEY_GROUP_HISTORY, null);
        if (groupJson != null) {
            try {
                JSONObject root = new JSONObject(groupJson);
                java.util.Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONArray arr = root.getJSONArray(key);
                    CopyOnWriteArrayList<GroupMessageEntry> list =
                            new CopyOnWriteArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject e = arr.getJSONObject(i);
                        list.add(new GroupMessageEntry(
                                e.optString("text", ""),
                                e.optLong("timestamp", 0L),
                                e.optString("senderPhone", ""),
                                e.optString("senderName", "")));
                    }
                    if (!list.isEmpty()) {
                        groupMessageHistory.put(key, list);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load group history", e);
            }
        }
    }

    /** Persists the current 1:1 message history to SharedPreferences. */
    private static void saveChatHistory() {
        if (prefs == null) return;
        try {
            JSONObject root = new JSONObject();
            for (String key : messageHistory.keySet()) {
                CopyOnWriteArrayList<MessageEntry> list = messageHistory.get(key);
                JSONArray arr = new JSONArray();
                for (MessageEntry entry : list) {
                    JSONObject e = new JSONObject();
                    e.put("text", entry.text);
                    e.put("timestamp", entry.timestamp);
                    arr.put(e);
                }
                root.put(key, arr);
            }
            prefs.edit().putString(KEY_CHAT_HISTORY, root.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to save chat history", e);
        }
    }

    /** Persists the current group message history to SharedPreferences. */
    private static void saveGroupHistory() {
        if (prefs == null) return;
        try {
            JSONObject root = new JSONObject();
            for (String key : groupMessageHistory.keySet()) {
                CopyOnWriteArrayList<GroupMessageEntry> list =
                        groupMessageHistory.get(key);
                JSONArray arr = new JSONArray();
                for (GroupMessageEntry entry : list) {
                    JSONObject e = new JSONObject();
                    e.put("text", entry.text);
                    e.put("timestamp", entry.timestamp);
                    e.put("senderPhone", entry.senderPhone);
                    e.put("senderName", entry.senderName);
                    arr.put(e);
                }
                root.put(key, arr);
            }
            prefs.edit().putString(KEY_GROUP_HISTORY, root.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to save group history", e);
        }
    }

    private final Context appContext;
    private final SettingsManager settingsManager;

    public NotificationHelper(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.settingsManager = new SettingsManager(context);
        initPrefs(context);
        createNotificationChannels();
    }

    // ---------------------------------------------------------------
    //  Channel creation (API 26+)
    // ---------------------------------------------------------------

    /**
     * Creates the two required notification channels.
     * Safe to call repeatedly — the OS ignores re-creation of existing channels.
     */
    public void createNotificationChannels() {

        NotificationManager nm = (NotificationManager)
                appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            Log.w(TAG, "NotificationManager is null — cannot create channels");
            return;
        }

        // ── Chat messages channel ──────────────────────────────────
        NotificationChannel chatChannel = new NotificationChannel(
                CHANNEL_CHAT,
                CHANNEL_CHAT_NAME,
                NotificationManager.IMPORTANCE_HIGH
        );
        chatChannel.setDescription(CHANNEL_CHAT_DESC);
        chatChannel.enableLights(true);
        chatChannel.enableVibration(true);
        chatChannel.setShowBadge(true);
        nm.createNotificationChannel(chatChannel);

        // ── News broadcasts channel ────────────────────────────────
        NotificationChannel newsChannel = new NotificationChannel(
                CHANNEL_NEWS,
                CHANNEL_NEWS_NAME,
                NotificationManager.IMPORTANCE_HIGH
        );
        newsChannel.setDescription(CHANNEL_NEWS_DESC);
        newsChannel.enableLights(true);
        newsChannel.enableVibration(true);
        newsChannel.setShowBadge(true);
        nm.createNotificationChannel(newsChannel);

        Log.i(TAG, "Notification channels created: chat_messages, news_broadcasts");
    }

    // ---------------------------------------------------------------
    //  Phone-to-display-name cache
    // ---------------------------------------------------------------

    /**
     * Registers a display name for the given phone number so subsequent
     * notifications use the human-readable name instead of the raw number.
     * Safe to call from any thread.
     */
    public static void registerDisplayName(@NonNull String phone,
                                            @NonNull String displayName) {
        if (phone.isEmpty()) return;
        displayNameCache.put(phone, displayName);
        Log.d(TAG, "Registered display name '" + displayName + "' for phone " + phone);
    }

    /**
     * Looks up the best-known display name for a phone number.
     * Returns the cached username if found, otherwise falls back to the phone.
     */
    @NonNull
    public static String lookupDisplayName(@NonNull String phone) {
        String name = displayNameCache.get(phone);
        return name != null ? name : phone;
    }

    /**
     * Returns a stable, non-negative notification ID derived from the sender's
     * phone number.  Using the same ID for the same sender means the OS
     * <em>updates</em> the existing notification rather than creating a new one,
     * which allows {@link NotificationCompat.MessagingStyle} to stack messages.
     */
    private static int notificationIdForSender(@NonNull String senderPhone) {
        return (CHANNEL_CHAT.hashCode() ^ senderPhone.hashCode()) & Integer.MAX_VALUE;
    }

    /**
     * Posts a high-priority notification for an incoming direct message,
     * using {@link NotificationCompat.MessagingStyle} for a rich
     * conversation-style notification.
     *
     * <p>Messages from the same sender are stacked into a single expandable
     * conversation notification by using a stable notification ID derived
     * from the sender's phone number and by keeping a per-sender message
     * history that is replayed into the style on every update.</p>
     *
     * @param senderPhone normalized phone number of the sender
     * @param messageText the incoming message text
     */
    public void notifyIncomingMessage(@NonNull String senderPhone,
                                      @NonNull String messageText) {
        if (!settingsManager.isChatNotificationEnabled()) {
            Log.d(TAG, "Chat notifications disabled — suppressing notification");
            return;
        }
        String displayName = lookupDisplayName(senderPhone);
        long now = System.currentTimeMillis();

        // ── Append this message to per-sender history ──────────────
        CopyOnWriteArrayList<MessageEntry> history =
                messageHistory.computeIfAbsent(senderPhone,
                        k -> new CopyOnWriteArrayList<>());
        history.add(new MessageEntry(messageText, now));

        // Cap history at 50 entries to prevent unbounded growth
        while (history.size() > 50) {
            history.remove(0);
        }
        saveChatHistory();

        // ── Build the Person representing the sender ───────────────
        Person sender = new Person.Builder()
                .setName(displayName)
                .setKey(senderPhone)
                .build();

        // ── Build the deep-link PendingIntent ──────────────────────
        Intent intent = new Intent(appContext, ChatActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(ChatActivity.EXTRA_RECIPIENT_PHONE, senderPhone);
        intent.putExtra(ChatActivity.EXTRA_RECIPIENT_NAME, displayName);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                appContext,
                notificationIdForSender(senderPhone),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // ── Build the MessagingStyle conversation ──────────────────
        NotificationCompat.MessagingStyle style =
                new NotificationCompat.MessagingStyle(sender)
                        .setConversationTitle(displayName);

        // Replay all accumulated messages (up to 50)
        for (MessageEntry entry : history) {
            style.addMessage(entry.text, entry.timestamp, sender);
        }

        // ── Build and post the notification ────────────────────────
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(appContext, CHANNEL_CHAT)
                        .setSmallIcon(R.drawable.ic_notification_chat)
                        .setStyle(style)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setShortcutId(senderPhone);

        NotificationManagerCompat nm = NotificationManagerCompat.from(appContext);
        try {
            nm.notify(CHANNEL_CHAT,
                    notificationIdForSender(senderPhone),
                    builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Notification post failed (missing permission): " + e.getMessage());
        }
    }

    /**
     * Returns a stable, non-negative notification ID derived from a group
     * conversation ID.  Using the same ID for the same group means the OS
     * <em>updates</em> the existing notification rather than creating a new one.
     */
    private static int notificationIdForGroup(@NonNull String groupId) {
        return ("group".hashCode() ^ groupId.hashCode()) & Integer.MAX_VALUE;
    }

    /**
     * Posts a high-priority notification for a <em>group</em> chat message,
     * using {@link NotificationCompat.MessagingStyle} with
     * {@code setGroupConversation(true)} so each message is attributed to
     * its actual sender.
     *
     * <p>Messages from different senders are stacked into a single expandable
     * conversation notification per group.  Each message shows its sender's
     * display name inside the conversation bubble.</p>
     *
     * @param groupId       stable ID identifying this group conversation
     * @param groupName     human-readable group name (shown as conversation title)
     * @param senderPhone   normalized phone number of the message sender
     * @param senderName    display name of the message sender
     * @param messageText   the incoming message text
     */
    public void notifyGroupMessage(@NonNull String groupId,
                                   @NonNull String groupName,
                                   @NonNull String senderPhone,
                                   @NonNull String senderName,
                                   @NonNull String messageText) {
        if (!settingsManager.isChatNotificationEnabled()) {
            Log.d(TAG, "Group chat notifications disabled — suppressing notification");
            return;
        }
        long now = System.currentTimeMillis();
        String resolvedSenderName = lookupDisplayName(senderPhone);
        if (resolvedSenderName.equals(senderPhone) && !senderName.isEmpty()) {
            resolvedSenderName = senderName;
        }

        // ── Append to per-group history ─────────────────────────────
        CopyOnWriteArrayList<GroupMessageEntry> history =
                groupMessageHistory.computeIfAbsent(groupId,
                        k -> new CopyOnWriteArrayList<>());
        history.add(new GroupMessageEntry(messageText, now,
                senderPhone, resolvedSenderName));
        while (history.size() > 50) {
            history.remove(0);
        }
        saveGroupHistory();

        // ── Build a Person representing the group itself ────────────
        Person groupPerson = new Person.Builder()
                .setName(groupName)
                .setKey(groupId)
                .build();

        // ── Build the deep-link PendingIntent ──────────────────────
        Intent intent = new Intent(appContext, ChatActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                appContext,
                notificationIdForGroup(groupId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // ── Build the MessagingStyle for group conversation ─────────
        NotificationCompat.MessagingStyle style =
                new NotificationCompat.MessagingStyle(groupPerson)
                        .setConversationTitle(groupName)
                        .setGroupConversation(true);

        // Replay all accumulated messages with per-sender attribution
        for (GroupMessageEntry entry : history) {
            Person msgSender = new Person.Builder()
                    .setName(entry.senderName)
                    .setKey(entry.senderPhone)
                    .build();
            style.addMessage(entry.text, entry.timestamp, msgSender);
        }

        // ── Build and post the notification ────────────────────────
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(appContext, CHANNEL_CHAT)
                        .setSmallIcon(R.drawable.ic_notification_chat)
                        .setStyle(style)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setShortcutId(groupId);

        NotificationManagerCompat nm = NotificationManagerCompat.from(appContext);
        try {
            nm.notify(CHANNEL_CHAT,
                    notificationIdForGroup(groupId),
                    builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Group notification post failed: " + e.getMessage());
        }
    }

    /**
     * Posts a high-priority notification for an incoming news broadcast.
     *
     * @param authorName display name or phone of the broadcaster
     * @param newsSnippet preview text of the news item
     */
    public void notifyIncomingNews(@NonNull String authorName,
                                   @NonNull String newsSnippet) {
        if (!settingsManager.isNewsNotificationEnabled()) {
            Log.d(TAG, "News notifications disabled — suppressing notification");
            return;
        }
        Intent intent = new Intent(appContext, NewsFeedActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                appContext,
                (int) (System.currentTimeMillis() + 1),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_NEWS)
                .setSmallIcon(R.drawable.ic_notification_news)
                .setContentTitle("News from " + authorName)
                .setContentText(newsSnippet)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(newsSnippet))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL);

        NotificationManagerCompat nm = NotificationManagerCompat.from(appContext);
        try {
            nm.notify(CHANNEL_NEWS, newsNotificationId++, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Notification post failed (missing permission): " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    //  Convenience helpers
    // ---------------------------------------------------------------

    /**
     * Posts a notification using a fully custom builder.
     * Useful when callers need to set extras, group keys, or actions.
     */
    public void notify(@NonNull String channelId,
                       int notificationId,
                       @NonNull NotificationCompat.Builder builder) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(appContext);
        try {
            nm.notify(channelId, notificationId, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Custom notification post failed: " + e.getMessage());
        }
    }

    /** Dismisses all notifications from both channels and clears message history. */
    public void cancelAll() {
        NotificationManagerCompat nm = NotificationManagerCompat.from(appContext);
        nm.cancelAll();
        messageHistory.clear();
        groupMessageHistory.clear();
        if (prefs != null) {
            prefs.edit()
                    .remove(KEY_CHAT_HISTORY)
                    .remove(KEY_GROUP_HISTORY)
                    .apply();
        }
    }
}
