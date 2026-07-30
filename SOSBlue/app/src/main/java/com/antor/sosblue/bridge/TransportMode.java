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
     * Heuristic SMS-capable check. The device declares
     * {@code android.hardware.telephony} AND either:
     *   • {@code getSimState()} reports READY/PIN/PUK/NETWORK_LOCKED
     *     (a SIM is present and unlocked), or
     *   • {@code READ_PHONE_STATE} is granted — assume the SIM exists
     *     and let the actual {@code SmsManager.send…} call fail at
     *     send-time if it doesn't.
     * <p>
     * NOTE: We deliberately do NOT call {@code getSubscriberId()}.
     * Samsung Knox (and several carriers) block IMSI reads even with
     * {@code READ_PHONE_STATE} granted, returning {@code null} and
     * logging
     * {@code W/TelephonyPermissions: reportAccessDeniedToReadIdentifiers}.
     * That false negative makes every Samsung phone look SIM-less and
     * permanently disables the SMS radio — which is exactly what we
     * observed on RRCW902V2YY.
     * <p>
     * Does NOT request any permissions itself; the runtime grant
     * happens via {@code ActivityCompat.requestPermissions} for
     * SEND_SMS / RECEIVE_SMS in {@code ChatActivity}.
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

            // ── Primary: SIM state ──────────────────────────────────────
            // Doesn't require READ_PHONE_STATE on most OEMs (Samsung, Pixel,
            // OnePlus) and is accurate on tablets with a cellular radio too.
            int simState;
            try { simState = tm.getSimState(); }
            catch (Throwable ignored) { simState = TelephonyManager.SIM_STATE_UNKNOWN; }
            switch (simState) {
                case TelephonyManager.SIM_STATE_READY:
                case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                    return true;
                default:
                    // SIM_STATE_ABSENT / UNKNOWN / NOT_READY / CARD_IO_ERROR
            }

            // ── Fallback: READ_PHONE_STATE granted means the OS already ──
            // accepted this app as phone-aware — trust the user-granted
            // permission and let SmsManager surface real errors.
            try {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.READ_PHONE_STATE)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            } catch (Throwable ignored) { }

            // ── Last-ditch: SmsManager presence check ───────────────────
            // Some non-telephony devices (certain tablets) still have a
            // SmsManager that throws on actual use, so this is a soft check
            // only — the real send path guards with explicit permission +
            // SIM-state checks.
            try {
                if (android.telephony.SmsManager.getDefault() != null) {
                    return true;
                }
            } catch (Throwable ignored) { }

            // ── Final verification: SEND_SMS already granted means the ───
            // user intends to use SMS, so we trust them.
            try {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.SEND_SMS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            } catch (Throwable ignored) { }

            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
