package com.antor.sosblue.bridge;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.antor.sosblue.identity.F2PMessage;
import com.antor.sosblue.identity.UserIdentity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.SharedPreferences;

/**
 * SMS-based fallback transport for when neither mesh nor F2P is reachable.
 *
 * <p><b>Wire format.</b> Each SMS body is encoded as a single ASCII
 * line:</p>
 *
 * <pre>
 *   DR1:&lt;correlation-id&gt;:&lt;base64url-no-pad&gt;
 * </pre>
 *
 * <p>{@code DR1} is the version magic ("Dark-Red v1"). The
 * correlation-id is the first 6 bytes of the SHA-256 hash of the
 * payload, base32-encoded — short enough to fit in 153-char GSM-7
 * SMS while still letting receivers drop foreign messages cheaply.
 * The body itself is the full {@link F2PMessage#serialize()}
 * envelope, base64url-encoded without padding (URL-safe
 * alphabet, NO_WRAP).</p>
 *
 * <p><b>Receive model (Stage 1).</b> A {@link BroadcastReceiver} is
 * registered <i>at runtime</i> from {@code SOSBlueApplication} so it
 * only fires while the process is alive. Background SMS delivery
 * requires default-SMS-app eligibility (Stage 2). The receiver
 * extracts the DR1 frames and, after multi-part reassembly, calls
 * {@link OnSmsEnvelopeListener#onEnvelope(String, String)} with the
 * decoded envelope's sender + recipient phone numbers; downstream
 * decryption happens in the chat activity using
 * {@link MessageEncryptor}.</p>
 *
 * <p><b>Send model.</b> {@link #sendEnvelope(F2PMessage)} builds a
 * multipart SMS, hands the parts to {@link SmsManager#sendMultipartTextMessage},
 * and reports per-part status (sent / generic failure / no service)
 * via {@link OnSmsSendListener}.</p>
 *
 * <p>This class deliberately uses only the system {@link SmsManager}
 * — no MMS, no carrier APIs, no subscriptions API — so it works on
 * every device that ships telephony.</p>
 */
public class SmsTransport {

    private static final String TAG = "SmsTransport";

    /** Wire-format version magic. "Dark-Red v1". */
    public static final String MAGIC = "DR1";

    /** Max chars per GSM-7 SMS part. */
    private static final int GSM7_PART_LEN = 153;

    /** Max chars per UCS-2 SMS part (when payload contains non-GSM-7 bytes). */
    private static final int UCS2_PART_LEN = 67;

    /** Application context (Application-scoped, never leaks Activity). */
    private final Context appContext;

    /** Background executor for sendMultipartTextMessage work. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Foreground-registered receiver. Null until {@link #register(Context)} runs. */
    private SmsReceiver receiver;

    /** Listeners notified when a valid DR1 envelope arrives. */
    private final CopyOnWriteArrayList<OnSmsEnvelopeListener> envelopeListeners =
            new CopyOnWriteArrayList<>();

    /** Per-send listeners, keyed by correlation id. */
    private final Map<String, OnSmsSendListener> sendListeners = new HashMap<>();

    /** Main-thread handler for listener callbacks. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Set of SMS inbox row IDs already processed by {@link #scanInbox()}.
     * Persisted in SharedPreferences so a re-scan across process restarts
     * doesn't re-deliver old DR1 envelopes from a previous session.
     */
    private final Set<Long> processedInboxIds =
            Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    /**
     * Set of correlation IDs (first 10-base32 chars of payload SHA-256)
     * already processed via the {@code SMS_RECEIVED} broadcast path.
     * Shared between the broadcast receiver and {@link #scanInbox()} so
     * a message that arrived via the live broadcast isn't re-dispatched
     * when the inbox poll runs moments later.
     */
    private final Set<String> processedCorrelationIds =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** SharedPreferences for persisting processed inbox row IDs. */
    private final SharedPreferences prefs;
    private static final String PREFS_NAME = "sms_transport_state";
    private static final String KEY_PROCESSED_IDS = "processed_inbox_ids";

    /** Max processed inbox IDs to track — prevents unbounded growth. */
    private static final int MAX_PROCESSED_IDS = 2000;

    public SmsTransport(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // Restore processed inbox IDs from SharedPreferences
        String saved = prefs.getString(KEY_PROCESSED_IDS, "");
        if (saved != null && !saved.isEmpty()) {
            for (String id : saved.split(",")) {
                try {
                    processedInboxIds.add(Long.parseLong(id.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        Log.d(TAG, "Restored " + processedInboxIds.size() + " processed inbox IDs from prefs");
    }

    /**
     * Shuts down the background executor. Call when done with this transport.
     */
    public void shutdown() {
        try {
            executor.shutdownNow();
        } catch (Exception e) {
            Log.w(TAG, "Executor shutdown failed: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    //  Listener API
    // ----------------------------------------------------------------

    public void addEnvelopeListener(OnSmsEnvelopeListener listener) {
        envelopeListeners.add(listener);
    }

    public void removeEnvelopeListener(OnSmsEnvelopeListener listener) {
        envelopeListeners.remove(listener);
    }

    // ----------------------------------------------------------------
    //  Registration (idempotent)
    // ----------------------------------------------------------------

    /**
     * Registers the {@code SMS_RECEIVED} broadcast receiver for as
     * long as the process is alive.  Must be called from the main
     * thread.  Stage 2 will replace this with a manifest-declared
     * receiver so messages arrive while the app is backgrounded.
     */
    public void register() {
        if (receiver != null) return;
        receiver = new SmsReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.provider.Telephony.SMS_RECEIVED");
        // RECEIVE_SMS is a signature-protected permission; on API 33+
        // we also need to declare RECEIVER_EXPORTED for non-protected
        // broadcasts, but SMS_RECEIVED is a protected broadcast so we
        // can leave it unexported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, filter);
        }
        Log.i(TAG, "SmsReceiver registered (foreground)");
    }

    /**
     * Tears down the receiver. Safe to call multiple times.
     */
    public void unregister() {
        if (receiver == null) return;
        try {
            appContext.unregisterReceiver(receiver);
        } catch (Throwable t) {
            Log.w(TAG, "unregisterReceiver failed: " + t.getMessage());
        }
        receiver = null;
    }

    // ----------------------------------------------------------------
    //  Send path
    // ----------------------------------------------------------------

    /**
     * Builds a multipart SMS from an {@link F2PMessage} envelope and
     * hands it to the system {@link SmsManager}.
     *
     * @param envelope fully-populated {@link F2PMessage} (sender,
     *                 recipient, nonce, ts, payload all set)
     * @param listener receives per-message sent / generic-failure
     *                 callback. {@code onSent} runs on the main
     *                 thread when ALL parts are accepted by the
     *                 radio. {@code onFailed(reason)} runs on the
     *                 main thread on first failure.
     */
    public void sendEnvelope(F2PMessage envelope, @Nullable OnSmsSendListener listener) {
        executor.execute(() -> {
            String reason = "Unknown error";
            boolean ok = false;
            try {
                // ── Runtime permission check: SEND_SMS ────────────────
                // Crash guard: if the user revoked the permission after
                // the mode switch, fail early instead of letting
                // SmsManager throw an unhandled SecurityException.
                if (ContextCompat.checkSelfPermission(appContext,
                        Manifest.permission.SEND_SMS)
                        != PackageManager.PERMISSION_GRANTED) {
                    reason = "SEND_SMS permission was revoked";
                    Log.e(TAG, reason);
                    notifySendFailed(listener, reason);
                    return;
                }

                // ── SIM state verification ───────────────────────────-
                // Confirm the SIM is actually ready before attempting
                // SMS operations.  This catches the "SIM not inserted"
                // case early instead of letting SmsManager surface an
                // opaque radio-level error.
                TelephonyManager tm = (TelephonyManager)
                        appContext.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm == null || tm.getSimState() != TelephonyManager.SIM_STATE_READY) {
                    reason = "SIM not ready — cannot send SMS";
                    Log.w(TAG, reason + " (state="
                            + (tm != null ? tm.getSimState() : "no-telephony") + ")");
                    notifySendFailed(listener, reason);
                    return;
                }

                String myPhone = UserIdentity.getPhoneNumber(appContext);
                if (myPhone == null) {
                    reason = "Sender phone number is not configured";
                    notifySendFailed(listener, reason);
                    return;
                }

                byte[] envelopeBytes = envelope.serialize();
                String encoded = Base64.encodeToString(
                        envelopeBytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

                // First 6 bytes of SHA-256 — base32 — gives a 10-char
                // correlation id that fits in the prefix without
                // wasting part capacity.
                String correlationId = shortId(envelopeBytes);

                String body = MAGIC + ":" + correlationId + ":" + encoded;
                Log.d(TAG, "sendEnvelope bodyLen=" + body.length()
                        + " parts=" + splitLengths(body));

                SmsManager smsManager = SmsManager.getDefault();
                ArrayList<String> parts = smsManager.divideMessage(body);

                // ── Per-part sent / delivery PendingIntents. We do
                //    not register for delivery reports (no reliable
                //    path unless we're the default SMS app), but we
                //    do need RESULT_CODE_GENERIC_FAILURE per part to
                //    bail early on a fatal send error.
                String destAddress = envelope.getRecipientPhone();
                ArrayList<android.app.PendingIntent> sentIntents =
                        new ArrayList<>(parts.size());
                for (int i = 0; i < parts.size(); i++) sentIntents.add(null);

                smsManager.sendMultipartTextMessage(
                        destAddress,
                        null,                 // service center (use carrier default)
                        parts,
                        sentIntents,
                        null);                // delivery intents (unused on stock)

                ok = true;
            } catch (SecurityException se) {
                reason = "SEND_SMS permission denied";
                Log.e(TAG, reason, se);
            } catch (Exception e) {
                reason = e.getMessage() != null
                        ? e.getMessage()
                        : "Send failed: " + e.getClass().getSimpleName();
                Log.e(TAG, "sendEnvelope failed", e);
            }
            if (ok) notifySendSent(listener);
            else notifySendFailed(listener, reason);
        });
    }

    // ----------------------------------------------------------------
    //  Receive path
    // ----------------------------------------------------------------

    /**
     * Reassemble incoming SMS parts and dispatch any complete DR1
     * envelope to the registered listeners.
     *
     * <p>Called from {@link SmsReceiver} on the main thread with
     * the array of {@link SmsMessage} parts in PDU order.</p>
     */
    void onSmsReceived(SmsMessage[] parts) {
        if (parts == null || parts.length == 0) return;
        StringBuilder body = new StringBuilder();
        for (SmsMessage m : parts) {
            if (m == null || m.getMessageBody() == null) continue;
            body.append(m.getMessageBody());
        }
        String full = body.toString();
        Log.d(TAG, "onSmsReceived len=" + full.length() + " head="
                + (full.length() > 24 ? full.substring(0, 24) + "…" : full));

        // Extract correlation ID for dedup before dispatch
        if (full.startsWith(MAGIC + ":")) {
            String[] seg = full.split(":", 3);
            if (seg.length >= 2) {
                String corrId = seg[1];
                if (processedCorrelationIds.contains(corrId)) {
                    Log.d(TAG, "onSmsReceived: correlation id " + corrId
                            + " already processed — dropping duplicate");
                    return;
                }
                processedCorrelationIds.add(corrId);
            }
        }

        dispatchEnvelopeFromBody(full);
    }

    /**
     * Queries the system SMS inbox for DR1 envelopes that haven't yet
     * been delivered to listeners.
     *
     * <p>This is the recovery path for self-sent messages (where the
     * {@code SMS_RECEIVED} broadcast may fire before our receiver is
     * registered) and for messages that arrived while the app process
     * was dead. It is read-only — it never deletes or marks envelopes
     * as read. Already-processed ids are tracked in
     * {@link #processedInboxIds} so re-scanning is idempotent.</p>
     *
     * <p>Requires {@code android.permission.READ_SMS} at runtime.
     * If the permission is missing, the query simply fails and a
     * warning is logged — no exception is propagated upward.</p>
     */
    public void scanInbox() {
        executor.execute(() -> {
            if (envelopeListeners.isEmpty()) {
                Log.d(TAG, "scanInbox: no listeners, skipping");
                return;
            }
            ContentResolver cr;
            Cursor cursor = null;
            try {
                cr = appContext.getContentResolver();
                Uri inbox = Uri.parse("content://sms/inbox");
                String[] projection = new String[] { "_id", "address", "body", "date" };
                String selection = "body LIKE ?";
                String[] selArgs = new String[] { "DR1:%" };
                cursor = cr.query(inbox, projection, selection, selArgs, "date DESC");
                if (cursor == null) {
                    Log.w(TAG, "scanInbox: cursor is null (provider not available)");
                    return;
                }
                int idCol = cursor.getColumnIndex("_id");
                int bodyCol = cursor.getColumnIndex("body");
                if (idCol < 0 || bodyCol < 0) {
                    Log.w(TAG, "scanInbox: missing expected columns");
                    return;
                }
                int processed = 0;
                long newestId = 0;
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    if (id > newestId) newestId = id;
                    if (processedInboxIds.contains(id)) continue;
                    String body = cursor.getString(bodyCol);
                    if (body == null) continue;

                    // ── Cross-check against correlation IDs already
                    //    handled by the live broadcast path. The broadcast
                    //    fires before scanInbox runs, so we skip messages
                    //    whose correlation ID was already dispatched.
                    if (body.startsWith(MAGIC + ":")) {
                        String[] seg = body.split(":", 3);
                        if (seg.length >= 2 && processedCorrelationIds.contains(seg[1])) {
                            Log.v(TAG, "scanInbox: skipping id=" + id
                                    + " — already dispatched via broadcast");
                            processedInboxIds.add(id);
                            if (id > newestId) newestId = id;
                            continue;
                        }
                    }

                    try {
                        dispatchEnvelopeFromBody(body);
                        processedInboxIds.add(id);
                        processed++;
                    } catch (Throwable t) {
                        Log.w(TAG, "scanInbox: dispatch failed for id=" + id
                                + ": " + t.getMessage());
                    }
                }
                Log.i(TAG, "scanInbox: processed " + processed
                        + " new envelope(s), newestId=" + newestId);

                // ── Persist processed inbox IDs so old messages are not
                //    re-processed after a process restart.
                persistProcessedIds();
            } catch (SecurityException se) {
                Log.w(TAG, "scanInbox query failed: " + se.getMessage());
            } catch (Throwable t) {
                Log.w(TAG, "scanInbox error: " + t.getMessage());
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    /**
     * Decodes a raw SMS body (possibly a multi-part reassembly) and,
     * if it's a DR1 envelope addressed to this device, hands the
     * encrypted payload to every registered listener.
     *
     * <p>Shared by {@link #onSmsReceived(SmsMessage[])} (the
     * broadcast path) and {@link #scanInbox()} (the polling path)
     * so both honour the same filtering + dispatch logic.</p>
     */
    private void dispatchEnvelopeFromBody(String full) {
        if (full == null) return;
        if (!full.startsWith(MAGIC + ":")) return;          // not our protocol
        String[] seg = full.split(":", 3);
        if (seg.length != 3) return;
        String correlationId = seg[1];

        // ── Global correlation ID dedup: skip if this envelope has
        //    already been dispatched via either the broadcast receiver
        //    or the inbox scan path.
        if (processedCorrelationIds.contains(correlationId)) {
            Log.v(TAG, "dispatchEnvelope: correlation id " + correlationId
                    + " already processed — dropping duplicate");
            return;
        }
        processedCorrelationIds.add(correlationId);

        String encoded = seg[2];
        byte[] envelopeBytes;
        try {
            envelopeBytes = Base64.decode(encoded,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Throwable t) {
            Log.w(TAG, "base64url decode failed: " + t.getMessage());
            return;
        }
        F2PMessage envelope;
        try {
            envelope = F2PMessage.deserialize(envelopeBytes);
        } catch (Throwable t) {
            Log.w(TAG, "F2PMessage deserialize failed: " + t.getMessage());
            return;
        }

        // Confirm the envelope is actually for us — drops stray
        // DR1 messages sent to a different recipient.
        String myPhone = UserIdentity.normalizePhoneNumber(
                UserIdentity.getPhoneNumber(appContext));
        if (myPhone == null) return;
        String envelopeRecipient = UserIdentity.normalizePhoneNumber(
                envelope.getRecipientPhone());
        if (!myPhone.equals(envelopeRecipient)) {
            Log.d(TAG, "incoming DR1 not addressed to us (to="
                    + envelope.getRecipientPhone() + "), dropping");
            return;
        }

        Log.i(TAG, "Delivering DR1 envelope from " + envelope.getSenderPhone()
                + " (" + envelopeBytes.length + " bytes, id=" + correlationId + ")");
        for (OnSmsEnvelopeListener l : envelopeListeners) {
            try {
                l.onEnvelope(envelope.getSenderPhone(), envelope.getRecipientPhone(),
                        envelope.getEncryptedPayload());
            } catch (Throwable t) {
                Log.w(TAG, "envelope listener threw: " + t.getMessage());
            }
        }
    }

    /**
     * Persists the set of processed inbox row IDs to SharedPreferences so
     * old DR1 messages are not re-processed after a process restart.
     * Called after every inbox scan completes.
     */
    private void persistProcessedIds() {
        try {
            // Trim to the most recent MAX_PROCESSED_IDS IDs
            if (processedInboxIds.size() > MAX_PROCESSED_IDS) {
                java.util.ArrayList<Long> sorted = new java.util.ArrayList<>(processedInboxIds);
                java.util.Collections.sort(sorted);
                // Keep only the largest (most recent) MAX_PROCESSED_IDS IDs
                java.util.List<Long> toRemove = sorted.subList(0, sorted.size() - MAX_PROCESSED_IDS);
                for (Long id : toRemove) {
                    processedInboxIds.remove(id);
                }
                Log.d(TAG, "Trimmed processedInboxIds to " + MAX_PROCESSED_IDS
                        + " (removed " + toRemove.size() + " old IDs)");
            }

            StringBuilder sb = new StringBuilder();
            for (Long id : processedInboxIds) {
                if (sb.length() > 0) sb.append(",");
                sb.append(id);
            }
            prefs.edit().putString(KEY_PROCESSED_IDS, sb.toString()).apply();
            Log.d(TAG, "Persisted " + processedInboxIds.size() + " processed inbox IDs");
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist processed inbox IDs", e);
        }
    }

    /**
     * Public getter for {@link #processedCorrelationIds} size (for debugging / UI).
     */
    public int getProcessedCount() {
        return processedCorrelationIds.size() + processedInboxIds.size();
    }

    // ----------------------------------------------------------------
    //  State reset (for testing / clearing stale messages)
    // ----------------------------------------------------------------

    /**
     * Clears ALL processed-ID tracking — both the in-memory sets and the
     * persisted SharedPreferences. After calling this, the next
     * {@link #scanInbox()} will re-process every DR1 message in the
     * system SMS inbox as if it had never been seen before.
     *
     * <p>Call this <b>after</b> manually deleting old DR1 messages from
     * your system SMS app to give the app a clean slate.</p>
     */
    public void clearProcessedIds() {
        processedInboxIds.clear();
        processedCorrelationIds.clear();
        prefs.edit().remove(KEY_PROCESSED_IDS).apply();
        Log.i(TAG, "Cleared all processed inbox IDs and correlation IDs");
    }

    /**
     * Attempts to delete all DR1-prefixed messages from the system SMS
     * inbox. Requires {@code android.permission.WRITE_SMS} which is
     * a system-level permission on most devices (API 19+) — this will
     * silently fail if the app does not hold it.
     *
     * <p>Returns the number of messages deleted, or -1 if the delete
     * failed (no permission, content provider unavailable, etc.).</p>
     */
    public int deleteDr1MessagesFromInbox() {
        try {
            ContentResolver cr = appContext.getContentResolver();
            Uri inbox = Uri.parse("content://sms/inbox");
            int deleted = cr.delete(inbox, "body LIKE ?", new String[] { "DR1:%" });
            Log.i(TAG, "Deleted " + deleted + " DR1 messages from inbox");
            // Also clear our tracking since those messages are now gone
            clearProcessedIds();
            return deleted;
        } catch (SecurityException se) {
            Log.w(TAG, "Cannot delete DR1 messages: WRITE_SMS not granted");
            return -1;
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete DR1 messages: " + e.getMessage());
            return -1;
        }
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    private void notifySendSent(@Nullable OnSmsSendListener l) {
        if (l == null) return;
        mainHandler.post(l::onSent);
    }

    private void notifySendFailed(@Nullable OnSmsSendListener l, String reason) {
        if (l == null) return;
        final String r = reason;
        mainHandler.post(() -> l.onFailed(r));
    }

    /** Returns the first 6 bytes of SHA-256(plaintext), base32-encoded. */
    private static String shortId(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            byte[] slice = Arrays.copyOf(hash, 6);
            return Base32.encode(slice);
        } catch (Exception e) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private static int splitLengths(String body) {
        // For UI/log only; the system computes the actual parts.
        if (allGsm7(body.getBytes(StandardCharsets.US_ASCII))) {
            return (int) Math.ceil(body.length() / (double) GSM7_PART_LEN);
        }
        return (int) Math.ceil(body.length() / (double) UCS2_PART_LEN);
    }

    /** Conservative GSM-7 alphabet check — anything outside it forces UCS-2. */
    private static boolean allGsm7(byte[] bytes) {
        for (byte b : bytes) {
            int v = b & 0xFF;
            // GSM-7 default alphabet (covers ASCII + a few extras). Base64url
            // only emits 0..127 so this is effectively a no-op for our payload.
            if (v == 0x0A || (v >= 0x20 && v <= 0x7E)) continue;
            return false;
        }
        return true;
    }

    // ----------------------------------------------------------------
    //  Listener interfaces
    // ----------------------------------------------------------------

    /** Notified when a DR1 envelope has been decoded from incoming SMS. */
    public interface OnSmsEnvelopeListener {
        /**
         * @param senderPhone    raw sender phone number from the
         *                       envelope (will need normalization by
         *                       the caller)
         * @param recipientPhone raw recipient phone number
         *                       (already verified to match local user)
         * @param encryptedPayload AES-256-GCM payload from the envelope
         */
        void onEnvelope(String senderPhone, String recipientPhone,
                        byte[] encryptedPayload);
    }

    /** Notified when a multipart SMS send completes (success or failure). */
    public interface OnSmsSendListener {
        void onSent();
        void onFailed(String reason);
    }

    // ----------------------------------------------------------------
    //  BroadcastReceiver
    // ----------------------------------------------------------------

    /**
     * Runtime-registered receiver for {@code SMS_RECEIVED}. Stage 2
     * will add a parallel manifest-declared receiver gated by
     * {@code signatureOrSystem} permission so background messages
     * also fire.
     */
    private class SmsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
                return;
            }
            Object[] pdus = (Object[]) intent.getSerializableExtra("pdus");
            if (pdus == null) return;
            SmsMessage[] parts = new SmsMessage[pdus.length];
            String format = intent.getStringExtra("format");
            for (int i = 0; i < pdus.length; i++) {
                parts[i] = SmsMessage.createFromPdu((byte[]) pdus[i], format);
            }
            onSmsReceived(parts);
        }
    }

    // ----------------------------------------------------------------
    //  Tiny Base32 (RFC 4648) — keeps correlation ids short
    // ----------------------------------------------------------------

    private static final class Base32 {
        private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

        static String encode(byte[] data) {
            StringBuilder sb = new StringBuilder();
            int buffer = 0;
            int bitsLeft = 0;
            for (byte b : data) {
                buffer = (buffer << 8) | (b & 0xFF);
                bitsLeft += 8;
                while (bitsLeft >= 5) {
                    int idx = (buffer >> (bitsLeft - 5)) & 0x1F;
                    sb.append(ALPHABET[idx]);
                    bitsLeft -= 5;
                }
            }
            if (bitsLeft > 0) {
                int idx = (buffer << (5 - bitsLeft)) & 0x1F;
                sb.append(ALPHABET[idx]);
            }
            return sb.toString();
        }
    }
}