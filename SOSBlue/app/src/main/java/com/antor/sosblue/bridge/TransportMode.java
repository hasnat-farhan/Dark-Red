package com.antor.sosblue.bridge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Transport mode for sending messages.
 *
 * <ul>
 *   <li>{@link #SOSBLUE_MESH} — Default BLE/WiFi-Direct P2P mesh relay</li>
 *   <li>{@link #F2P_SERVERLESS} — Routes via WanderingFibreEngine</li>
 *   <li>{@link #SMS} — Fallback using Android Telephony SmsManager</li>
 * </ul>
 */
public enum TransportMode {

    SOSBLUE_MESH("SOSBlue", R.drawable.nearby),
    F2P_SERVERLESS("F2P", R.drawable.search),   // reusing search icon as "network" glyph
    SMS("SMS", R.drawable.text);                 // reusing text icon

    private static final String PREFS_NAME = "transport_prefs";
    private static final String KEY_MODE = "transport_mode";

    private final String label;
    private final int iconResId;

    TransportMode(String label, int iconResId) {
        this.label = label;
        this.iconResId = iconResId;
    }

    public String getLabel()          { return label; }
    public int getIconResId()         { return iconResId; }

    // ---------------------------------------------------------------
    //  SharedPreferences persistence
    // ---------------------------------------------------------------

    public static TransportMode load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String name = prefs.getString(KEY_MODE, SOSBLUE_MESH.name());
        try { return valueOf(name); }
        catch (IllegalArgumentException e) { return SOSBLUE_MESH; }
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, name())
                .apply();
    }
}
