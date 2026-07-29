package com.antor.sosblue.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;

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

    /**
     * Loads the persisted transport mode.
     * <p>
     * Fix #3: In an isolated (sandboxed) child-process the system
     * {@code UserManager} may be null, causing a {@code NullPointerException}
     * when {@code getSharedPreferences} is called.  We guard against this
     * by returning the default mode immediately.
     */
    public static TransportMode load(Context context) {
        if (isIsolatedProcess()) {
            return SOSBLUE_MESH;  // safe default – no storage access
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String name = prefs.getString(KEY_MODE, SOSBLUE_MESH.name());
        try { return valueOf(name); }
        catch (IllegalArgumentException e) { return SOSBLUE_MESH; }
    }

    /**
     * Persists the selected transport mode.
     * <p>
     * Fix #3: No-op in isolated processes where storage is unavailable.
     */
    public void save(Context context) {
        if (isIsolatedProcess()) {
            return;  // silently skip – no storage in sandboxed process
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, name())
                .apply();
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    /**
     * Returns {@code true} when running inside an isolated / sandboxed
     * child process where privileged system services are unavailable.
     */
    private static boolean isIsolatedProcess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return Process.isIsolated();
        }
        return false;
    }
}
