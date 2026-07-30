package com.antor.sosblue.bridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.telephony.TelephonyManager;

import com.antor.sosblue.R;

/**
 * Transport mode for sending messages.
 *
 * <ul>
 *   <li>{@link #SOSBLUE_MESH}   — Default native BLE/WiFi-Direct P2P mesh relay</li>
 *   <li>{@link #F2P_SERVERLESS} — Routes via WanderingFibreEngine</li>
 *   <li>{@link #SMS_FALLBACK}   — Last-resort carrier SMS (only when nothing else works)</li>
 * </ul>
 */
public enum TransportMode {

    SOSBLUE_MESH("SOSBlue Mesh", R.drawable.nearby),
    F2P_SERVERLESS("F2P Serverless", R.drawable.search),
    SMS_FALLBACK("SMS Relay", R.drawable.ic_sms);

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
    @SuppressLint("NewApi")
    private static boolean isIsolatedProcess() {
        return Process.isIsolated();
    }

    // ----------------------------------------------------------------
    //  Capability check
    // ----------------------------------------------------------------

    /**
     * Returns {@code true} when this transport can be used on the
     * device. Mesh + F2P are always available; SMS requires a SIM
     * with active telephony (telephony feature flag + non-empty IMEI
     * or subscriber id). Tablets without a SIM will report SMS
     * unavailable and the UI should disable the radio button.
     */
    public boolean isAvailable(Context context) {
        switch (this) {
            case SOSBLUE_MESH:
            case F2P_SERVERLESS:
                return true;
            case SMS_FALLBACK:
                return hasTelephony(context);
            default:
                return false;
        }
    }

    /**
     * Heuristic SMS-capable check. Mirrors Android's stock messaging
     * rule: the device declares {@code android.hardware.telephony} and
     * has a SIM (subscriberId non-empty).  Does NOT request any
     * permissions; permission gating happens at runtime via
     * {@code ActivityCompat.requestPermissions} for SEND_SMS /
     * RECEIVE_SMS.
     */
    private static boolean hasTelephony(Context context) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            boolean hasFeature = pm.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_TELEPHONY);
            if (!hasFeature) return false;
            TelephonyManager tm = (TelephonyManager)
                    context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return false;
            // subscriberId is null when no SIM is present. Requires
            // READ_PHONE_STATE permission on API 31+, but only as a
            // gated runtime permission — null check is acceptable
            // here and won't crash if the permission is denied; it
            // will simply return null and we treat that as
            // "no SIM → unavailable".
            @SuppressLint("MissingPermission")
            String subscriberId = tm.getSubscriberId();
            return subscriberId != null && !subscriberId.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
