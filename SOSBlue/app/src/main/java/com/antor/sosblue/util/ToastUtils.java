package com.antor.sosblue.util;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

/**
 * Utility that prevents Toast stacking by cancelling any previously shown
 * Toast before displaying a new one. All messages use short duration so
 * they auto-dismiss quickly and never linger over the keyboard or UI.
 */
public final class ToastUtils {

    @Nullable
    private static Toast currentToast;

    private ToastUtils() {}

    /**
     * Shows a short-duration Toast, cancelling any previous Toast first.
     *
     * @param context  application or activity context
     * @param message  the text to display
     */
    public static void showShort(@NonNull Context context, @NonNull String message) {
        cancelCurrent();
        currentToast = Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT);
        currentToast.show();
    }

    /**
     * Shows a short-duration Toast from a string resource.
     *
     * @param context application or activity context
     * @param resId   string resource to display
     */
    public static void showShort(@NonNull Context context, @StringRes int resId) {
        cancelCurrent();
        currentToast = Toast.makeText(context.getApplicationContext(), resId, Toast.LENGTH_SHORT);
        currentToast.show();
    }

    /**
     * Immediately cancels any Toast that is currently visible or queued.
     */
    public static void cancelCurrent() {
        if (currentToast != null) {
            currentToast.cancel();
            currentToast = null;
        }
    }
}
