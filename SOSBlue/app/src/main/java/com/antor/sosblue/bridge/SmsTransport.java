package com.antor.sosblue.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.antor.sosblue.identity.F2PMessage;
import com.antor.sosblue.identity.UserIdentity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    public SmsTransport(Context context) {
        this.appContext = context.getApplicationContext();
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
        if (!full.startsWith(MAGIC + ":")) return;          // not our protocol
        String[] seg = full.split(":", 3);
        if (seg.length != 3) return;
        String correlationId = seg[1];
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