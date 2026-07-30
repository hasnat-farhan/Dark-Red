package com.antor.sosblue;

import android.annotation.SuppressLint;
import android.app.Application;
import android.os.Build;
import android.os.Process;
import android.util.Log;

/**
 * Custom {@link Application} that guards early initialisation so that
 * heavy work (SharedPreferences, storage, UI helpers) is never executed
 * inside an isolated / sandboxed child process (e.g. WebView renderer,
 * shell-packer subprocess, or any process declared with
 * {@code android:isolated="true"}).
 *
 * <h3>Why this matters</h3>
 * Android may spawn isolated child processes for security sandboxes.
 * In those processes, system services such as {@code UserManager} and
 * {@code SharedPreferences} may not be available, causing
 * {@code NullPointerException} during {@code ContextImpl.getSharedPreferences}.
 * By checking {@link Process#isIsolated()} we skip all such calls.
 */
public class SOSBlueApplication extends Application {

    private static final String TAG = "SOSBlueApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        // ── Fix #3: Guard against isolated-process crashes ──────────
        if (isIsolatedProcess()) {
            Log.d(TAG, "Running in isolated process – skipping app initialisation");
            return;
        }

        // ── Global uncaught exception handler ───────────────────────
        // Catches any exception that would otherwise crash the app,
        // logs it, and prevents the OS from showing a sudden close.
        // The app will still exit, but without the jarring ANR dialog,
        // and we get the stack trace in logcat.
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "UNCAUGHT EXCEPTION in thread " + thread.getName(), throwable);
            // Log key context
            Log.e(TAG, "Process: " + Build.MODEL + " (" + Build.VERSION.SDK_INT + ")");
            // Let the default handler terminate the process
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });

        // Normal initialisation for the main / non-isolated processes.
        Log.i(TAG, "SOSBlueApplication initialised (pid=" + Process.myPid() + ")");
    }

    @Override
    public void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);

        // ── Fix #3: Early guard ────────────────────────────────────
        // attachBaseContext is called before onCreate.  We must avoid
        // any SharedPreferences or storage access here when running in
        // an isolated process because UserManager may be null.
        if (isIsolatedProcess()) {
            Log.d(TAG, "attachBaseContext in isolated process – bailing out early");
            return;
        }
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    /**
     * Returns {@code true} when the current process is an isolated /
     * sandboxed child process where privileged system services are
     * unavailable.
     */
    @SuppressLint("NewApi")
    private boolean isIsolatedProcess() {
        return Process.isIsolated();
    }
}
