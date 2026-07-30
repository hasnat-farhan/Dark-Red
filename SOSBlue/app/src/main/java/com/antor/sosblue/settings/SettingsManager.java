package com.antor.sosblue.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Manages user-facing settings persisted in SharedPreferences.
 *
 * <p>Currently supports notification toggles (chat + news). Written as a
 * lightweight helper so callers (NotificationHelper, SettingsActivity)
 * share the same preference keys without coupling directly to each other.</p>
 */
public final class SettingsManager {

    private static final String PREFS_NAME = "sosblue_settings";
    private static final String KEY_NOTIFY_CHAT = "notify_chat";
    private static final String KEY_NOTIFY_NEWS = "notify_news";

    private static final boolean DEFAULT_NOTIFY_CHAT = true;
    private static final boolean DEFAULT_NOTIFY_NEWS = true;

    private final SharedPreferences prefs;

    public SettingsManager(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------
    //  Chat notifications
    // ---------------------------------------------------------------

    /** Returns {@code true} when chat (1:1) notifications are enabled. */
    public boolean isChatNotificationEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_CHAT, DEFAULT_NOTIFY_CHAT);
    }

    /** Enables or disables chat message notifications. */
    public void setChatNotificationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_CHAT, enabled).apply();
    }

    // ---------------------------------------------------------------
    //  News notifications
    // ---------------------------------------------------------------

    /** Returns {@code true} when news broadcast notifications are enabled. */
    public boolean isNewsNotificationEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_NEWS, DEFAULT_NOTIFY_NEWS);
    }

    /** Enables or disables news broadcast notifications. */
    public void setNewsNotificationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_NEWS, enabled).apply();
    }

    // ---------------------------------------------------------------
    //  Bulk
    // ---------------------------------------------------------------

    /** Resets all settings to their defaults. */
    public void resetToDefaults() {
        prefs.edit()
                .putBoolean(KEY_NOTIFY_CHAT, DEFAULT_NOTIFY_CHAT)
                .putBoolean(KEY_NOTIFY_NEWS, DEFAULT_NOTIFY_NEWS)
                .apply();
    }
}
