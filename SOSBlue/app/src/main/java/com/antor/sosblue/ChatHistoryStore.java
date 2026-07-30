package com.antor.sosblue;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.antor.sosblue.identity.UserIdentity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-recipient chat history persistence.
 *
 * <p>One JSON file per normalized phone number under {@code getFilesDir()/chat/}.
 * The JSON shape is {@code {"v":1,"msgs":[ MessageModel.toJson(), ... ]}}.</p>
 *
 * <p>All disk I/O is performed off the main thread on a dedicated single-thread
 * executor. Results are dispatched back via a {@link Callback} on the main
 * looper so callers don't need to worry about thread context.</p>
 */
public class ChatHistoryStore {

    private static final String TAG = "ChatHistoryStore";

    /** Bumped when the on-disk schema changes. */
    private static final int SCHEMA_VERSION = 1;

    /** Filename-safe normalisation fallback if UserIdentity returns null. */
    private static final String FILENAME_SAFE_FALLBACK_PREFIX = "unknown_";

    private final Context appContext;
    private final ExecutorService io;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public ChatHistoryStore(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ChatHistoryStore-IO");
            t.setDaemon(true);
            return t;
        });
    }

    /** Convenience: pass an Activity context directly. */
    public ChatHistoryStore(@NonNull android.app.Activity activity) {
        this((Context) activity);
    }

    /** Release the background executor. Call from Activity#onDestroy. */
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            io.shutdown();
        }
    }

    // ── Public API ───────────────────────────────────────────────

    /** Callback for async load. {@code messages} is never null; empty on miss. */
    public interface LoadCallback {
        void onLoaded(@NonNull String phone, @NonNull List<MessageModel> messages);
    }

    /** Callback for async save. */
    public interface SaveCallback {
        void onSaved(@NonNull String phone, boolean success);
    }

    /** Load history for {@code phone} (normalized). */
    public void loadAsync(@Nullable String phone, @NonNull LoadCallback callback) {
        final String key = normalizeKey(phone);
        io.execute(() -> {
            List<MessageModel> out = readFromDisk(key);
            android.os.Handler main = new android.os.Handler(
                    android.os.Looper.getMainLooper());
            main.post(() -> callback.onLoaded(key, out));
        });
    }

    /** Persist {@code messages} for {@code phone} (normalized). */
    public void saveAsync(@Nullable String phone, @NonNull List<MessageModel> messages,
                          @Nullable SaveCallback callback) {
        final String key = normalizeKey(phone);
        // Snapshot the list so the caller can keep mutating the live adapter list.
        final List<MessageModel> snapshot = new ArrayList<>(messages);
        io.execute(() -> {
            boolean ok = writeToDisk(key, snapshot);
            if (callback != null) {
                final boolean successFinal = ok;
                android.os.Handler main = new android.os.Handler(
                        android.os.Looper.getMainLooper());
                main.post(() -> callback.onSaved(key, successFinal));
            }
        });
    }

    /** Delete the history file for {@code phone} (normalized). */
    public void clearAsync(@Nullable String phone) {
        final String key = normalizeKey(phone);
        io.execute(() -> {
            File f = fileFor(key);
            if (f.exists()) {
                if (!f.delete()) {
                    Log.w(TAG, "Failed to delete history file " + f.getAbsolutePath());
                }
            }
        });
    }

    // ── Internals ────────────────────────────────────────────────

    @NonNull
    private String normalizeKey(@Nullable String phone) {
        if (phone == null) return FILENAME_SAFE_FALLBACK_PREFIX + "null";
        String trimmed = phone.trim();
        if (trimmed.isEmpty()) return FILENAME_SAFE_FALLBACK_PREFIX + "empty";
        String normalized = UserIdentity.normalizePhoneNumber(trimmed);
        if (normalized == null || normalized.isEmpty()) {
            // Fall back to a sanitised version of the raw input so we never
            // accidentally clobber history for unknown formats.
            String sanitized = trimmed.replaceAll("[^A-Za-z0-9+]", "_");
            return FILENAME_SAFE_FALLBACK_PREFIX + sanitized;
        }
        return normalized;
    }

    @NonNull
    private File fileFor(@NonNull String key) {
        File dir = new File(appContext.getFilesDir(), "chat");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create chat history dir " + dir.getAbsolutePath());
        }
        // Keys already contain only [0-9+] + prefix, but be defensive.
        String safe = key.replaceAll("[^A-Za-z0-9+_]", "_");
        return new File(dir, safe + ".json");
    }

    @NonNull
    private List<MessageModel> readFromDisk(@NonNull String key) {
        File f = fileFor(key);
        if (!f.exists() || f.length() == 0) return Collections.emptyList();
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String json = new String(bytes, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            int version = root.optInt("v", SCHEMA_VERSION);
            if (version > SCHEMA_VERSION) {
                Log.w(TAG, "History file version " + version + " > supported "
                        + SCHEMA_VERSION + ", skipping");
                return Collections.emptyList();
            }
            JSONArray arr = root.optJSONArray("msgs");
            if (arr == null) return Collections.emptyList();
            List<MessageModel> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                try {
                    out.add(MessageModel.fromJson(o));
                } catch (JSONException e) {
                    Log.w(TAG, "Skipping malformed history entry #" + i, e);
                }
            }
            return out;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Failed to read history for " + key, e);
            return Collections.emptyList();
        }
    }

    private boolean writeToDisk(@NonNull String key, @NonNull List<MessageModel> messages) {
        File f = fileFor(key);
        try {
            JSONObject root = new JSONObject();
            root.put("v", SCHEMA_VERSION);
            JSONArray arr = new JSONArray();
            for (MessageModel m : messages) {
                arr.put(m.toJson());
            }
            root.put("msgs", arr);
            // Write atomically: temp file + rename, so a crash mid-write
            // never leaves the user with a corrupted history.
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (!tmp.renameTo(f)) {
                // Some filesystems require delete-then-rename.
                if (f.exists() && !f.delete()) {
                    Log.w(TAG, "Could not delete stale history file before rename");
                }
                if (!tmp.renameTo(f)) {
                    Log.w(TAG, "Atomic rename failed, falling back to copy");
                    Files.copy(tmp.toPath(), f.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
            return true;
        } catch (JSONException | IOException e) {
            Log.w(TAG, "Failed to write history for " + key, e);
            return false;
        }
    }
}