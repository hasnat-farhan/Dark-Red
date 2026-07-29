package com.antor.sosblue.identity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;

import androidx.annotation.Nullable;

/**
 * Persisted user identity (username + phone number) used as the unique
 * peer identifier across the F2P mesh network.
 *
 * <p>The phone number (E.164 format) serves as the primary unique key
 * for message targeting, address routing, and encryption key derivation.</p>
 */
public final class UserIdentity {

    private static final String PREFS_NAME = "f2p_user_identity";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PHONE = "phone_number";

    private UserIdentity() {}

    // ---------------------------------------------------------------
    //  Persistence
    // ---------------------------------------------------------------

    /**
     * Saves the user identity to SharedPreferences.
     *
     * @param context  Android context
     * @param username display name
     * @param phone    E.164 phone number (e.g. +8801XXXXXXXX)
     */
    public static void save(Context context, String username, String phone) {
        if (isIsolatedProcess()) return;
        getPrefs(context).edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_PHONE, phone)
                .apply();
    }

    /**
     * Returns the saved username, or null if not yet registered.
     */
    @Nullable
    public static String getUsername(Context context) {
        if (isIsolatedProcess()) return null;
        return getPrefs(context).getString(KEY_USERNAME, null);
    }

    /**
     * Returns the saved phone number (E.164), or null if not yet registered.
     */
    @Nullable
    public static String getPhoneNumber(Context context) {
        if (isIsolatedProcess()) return null;
        return getPrefs(context).getString(KEY_PHONE, null);
    }

    /**
     * Returns true if the user has completed onboarding (both username
     * and phone number are set).
     */
    public static boolean isRegistered(Context context) {
        return !TextUtils.isEmpty(getUsername(context))
                && !TextUtils.isEmpty(getPhoneNumber(context));
    }

    /**
     * Clears the stored identity (e.g. for sign-out or re-onboarding).
     */
    public static void clear(Context context) {
        if (isIsolatedProcess()) return;
        getPrefs(context).edit().clear().apply();
    }

    // ---------------------------------------------------------------
    //  Phone number normalization
    // ---------------------------------------------------------------

    /**
     * Normalizes a phone number for consistent matching.
     * <p>
     * Strips all spaces, hyphens, parentheses, dots, and other non-digit
     * characters EXCEPT for a leading '+' sign. The result is a clean
     * E.164-compatible string suitable for use as:
     * <ul>
     *   <li>Encryption key derivation context</li>
     *   <li>Recipient address matching in the mesh routing layer</li>
     *   <li>JSON payload serialization for cross-device comparison</li>
     * </ul>
     * </p>
     *
     * <pre>{@code
     *   normalizePhoneNumber("+880 1712-345678")  → "+8801712345678"
     *   normalizePhoneNumber("+1 (555) 123-4567") → "+15551234567"
     *   normalizePhoneNumber("01712-345678")      → "01712345678"
     *   normalizePhoneNumber(null)                → null
     * }</pre>
     *
     * @param phone raw phone input (may contain formatting characters)
     * @return normalized phone number with only digits and optional leading '+',
     *         or {@code null} if the input was null
     */
    @Nullable
    public static String normalizePhoneNumber(@Nullable String phone) {
        if (phone == null) return null;
        // Preserve leading '+' if present, strip everything else that is not a digit
        boolean hasPlus = phone.startsWith("+");
        String digitsOnly = phone.replaceAll("[^\\d]", "");
        return hasPlus ? "+" + digitsOnly : digitsOnly;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean isIsolatedProcess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return Process.isIsolated();
        }
        return false;
    }
}
