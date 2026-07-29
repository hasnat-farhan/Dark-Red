package com.antor.sosblue.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import com.antor.sosblue.R;

/**
 * Transport mode for sending messages.
 *
 * <ul>
 *   <li>{@link #SOSBLUE_MESH} — Default native BLE/WiFi-Direct P2P mesh relay</li>
 *   <li>{@link #F2P_SERVERLESS} — Routes via WanderingFibreEngine</li>
 * </ul>
 */
public enum TransportMode {

    SOSBLUE_MESH("SOSBlue Mesh", R.drawable.nearby),
    F2P_SERVERLESS("F2P Serverless", R.drawable.search);

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
