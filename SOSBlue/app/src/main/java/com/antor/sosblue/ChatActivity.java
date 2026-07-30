package com.antor.sosblue;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.antor.sosblue.util.ToastUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.antor.f2p.engine.api.EngineCallback;
import com.antor.f2p.engine.api.EngineConfig;
import com.antor.f2p.engine.api.FibrePacket;
import com.antor.sosblue.bridge.F2PBridge;
import com.antor.sosblue.bridge.TransportMode;
import com.antor.sosblue.inbox.ConversationRegistry;
import com.antor.sosblue.identity.F2PMessage;
import com.antor.sosblue.identity.MessageEncryptor;
import com.antor.sosblue.identity.UserIdentity;
import com.antor.sosblue.identity.JsonPayloadHelper;

import android.app.AlertDialog;
import android.util.Base64;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;

public class ChatActivity extends AppCompatActivity {

    /** Intent extra key for pre-filling the recipient phone field (for E2E encryption). */
    public static final String EXTRA_RECIPIENT_PHONE = "com.antor.sosblue.RECIPIENT_PHONE";
    /** Intent extra key for the display name shown in the recipient field. */
    public static final String EXTRA_RECIPIENT_NAME = "com.antor.sosblue.RECIPIENT_NAME";

    // Core
    private F2PBridge bridge;
    private boolean engineReady;
    private boolean f2pRequested;

    /** Guards against re-entrant mode switching while a transition is in progress. */
    private boolean isModeSwitching = false;

    /** Tracks the currently active transport mode to detect same-mode calls. */
    private TransportMode currentTransportMode = null;

    /**
     * Set to {@code true} when the F2P engine is starting asynchronously
     * (via {@link F2PBridge#startEngineAsync}). The {@code finally} block
     * in {@link #onTransportModeChanged(TransportMode)} checks this flag
     * before dismissing the mode-switch dialog: if the async engine is
     * still starting, we intentionally leave the dialog showing and wait
     * for {@code onEngineStarted} / {@code onEngineError} to dismiss it.
     * <p>
     * Without this guard, the {@code finally} block would see the dialog
     * still showing (engine hasn't started yet) and prematurely call
     * {@code dismissModeSwitchDialog(false)}, which resets
     * {@code isModeSwitching = false} and allows a SECOND mode switch
     * while the first engine init is still in progress. This causes
     * concurrent engine starts, socket conflicts, and ultimately kicks
     * the user out of the chat activity.
     * </p>
     */
    private volatile boolean awaitingF2pEngine = false;

    /**
     * Deferred mode switch — set when the user taps a different radio button
     * while a mode switch is already in progress. Processed immediately after
     * the current switch completes (in {@link #dismissModeSwitchDialog}).
     */
    private TransportMode pendingMode = null;

    /**
     * Non-cancelable progress dialog shown during transport mode transitions.
     * Dismissed automatically when the switch completes or after 1.5s timeout.
     */
    private AlertDialog modeSwitchDialog;

    /** Timeout handler for the mode-switch dialog safety net. */
    private final Handler modeSwitchTimeoutHandler = new Handler(Looper.getMainLooper());

    /** Runnable that dismisses the mode-switch dialog if it times out. */
    private Runnable modeSwitchTimeoutRunnable;

    /** The indeterminate spinner inside the mode-switch dialog, kept for replacement on success. */
    @Nullable
    private ProgressBar modeSwitchSpinner;

    /**
     * Locally-created {@link com.antor.sosblue.bridge.SmsTransport} — only
     * non-null when ChatActivity booted the carrier ahead of any SMS send
     * (so the receive pipe is wired before the user picks "SMS Relay").
     * Unregistered in {@link #onDestroy()} to release the receiver.
     */
    private com.antor.sosblue.bridge.SmsTransport pendingSmsTransport;
    /** Listener we attached so it can be removed in onDestroy(). */
    private com.antor.sosblue.bridge.SmsTransport.OnSmsEnvelopeListener smsEnvelopeListener;

    /** Permission launcher for Wi-Fi/location permissions needed on Android 11 (Oppo). */
    private ActivityResultLauncher<String[]> wifiPermissionLauncher;

    /** Permission launcher for Bluetooth + Wi-Fi (Mesh mode). */
    private ActivityResultLauncher<String[]> meshPermissionLauncher;

    /** Permission launcher for SMS (SEND_SMS + RECEIVE_SMS + READ_PHONE_STATE). */
    private ActivityResultLauncher<String[]> smsPermissionLauncher;

    /** Permission launcher for POST_NOTIFICATIONS (Android 13+). */
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    /** Permission launcher for WRITE_EXTERNAL_STORAGE (Android 9 and below). */
    private ActivityResultLauncher<String> storagePermissionLauncher;

    /** Holds the pending media message while waiting for storage permission grant. */
    private MessageModel pendingDownloadMessage;

    /**
     * Saved transport mode BEFORE the user tapped SMS Relay — used to
     * revert the radio when permission is denied or SIM is absent.
     */
    private int lastSavedRadioId = R.id.rb_f2p_serverless;

    // Chat
    private ChatAdapter chatAdapter;

    /** Full unfiltered message list for search filtering. */
    private final java.util.List<MessageModel> allMessages = new java.util.ArrayList<>();

    /** Search bar visibility state. */
    private boolean searchVisible = false;

    /**
     * Per-recipient chat-history persistence. Created in {@link #onCreate}
     * and shut down in {@link #onDestroy}.
     */
    private ChatHistoryStore historyStore;

    /**
     * Normalised phone number of the currently-active chat thread. Used
     * as the key for both loading and saving history.  Empty string while
     * no recipient has been entered.
     */
    private String currentRecipientPhone = "";

    // Media picker — supports both images AND videos
    private ActivityResultLauncher<String[]> mediaPickerLauncher;

    // Views
    private RadioGroup transportRadioGroup;
    private ProgressBar bufferingProgress;
    private View loadingContainer;
    private View sendButton;
    private CardView peerBarCard;
    private RecyclerView peerBarRecyclerView;
    private PeerDiscoveryAdapter peerBarAdapter;
    private TextView peerCountBadge;
    private EditText inputRecipientPhone;
    private TextView encryptionBadge;

    /** Search input field reference for filtering. */
    private EditText searchInput;

    // Download progress views
    private View downloadProgressContainer;
    private ProgressBar downloadProgressBar;
    private TextView downloadProgressText;

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            initializedOnCreate();
        } catch (Exception e) {
            Log.e("ChatActivity", "FATAL: ChatActivity.onCreate crashed", e);
            android.widget.Toast.makeText(this,
                    "Chat crashed: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * Extracted main initialisation body so that a single try-catch in
     * {@link #onCreate(Bundle)} can shield the entire startup sequence
     * and surface crashes to the user instead of silently exiting to
     * the home screen.
     */
    private void initializedOnCreate() {

        // ── F2P Identity Gate ──────────────────────────────────────
        // Redirect to sign-in if user identity is not yet registered
        if (!UserIdentity.isRegistered(this)) {
            startActivity(new android.content.Intent(this,
                    com.antor.sosblue.identity.SignInActivity.class));
            finish();
            return;
        }

        // ── Runtime permission launcher for Wi-Fi/location ──────────
        // On Android 10-12, ACCESS_FINE_LOCATION is required for Wi-Fi
        // scanning and Wi-Fi Direct. On Android 13+, NEARBY_WIFI_DEVICES
        // replaces it and may be auto-granted. Oppo ColorOS on Android
        // 11 in particular enforces this strictly.
        wifiPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    boolean allGranted = true;
                    StringBuilder denied = new StringBuilder();
                    for (java.util.Map.Entry<String, Boolean> entry : result.entrySet()) {
                        if (!entry.getValue()) {
                            allGranted = false;
                            if (denied.length() > 0) denied.append(", ");
                            denied.append(entry.getKey());
                        }
                    }
                    if (!allGranted) {
                        Log.w("ChatActivity", "Wi-Fi permissions denied: " + denied);
                        View __root = findViewById(R.id.root);
                        if (__root != null) {
                            Snackbar.make(__root,
                                    "Wi-Fi permissions needed for peer discovery & mesh",
                                    Snackbar.LENGTH_LONG).show();
                        }
                    } else {
                        Log.i("ChatActivity", "All Wi-Fi/location permissions granted");
                    }
                });

        // ── Runtime permission launcher for Bluetooth + Wi-Fi (Mesh mode) ──
        meshPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    boolean allGranted = true;
                    StringBuilder denied = new StringBuilder();
                    for (java.util.Map.Entry<String, Boolean> entry : result.entrySet()) {
                        if (!entry.getValue()) {
                            allGranted = false;
                            if (denied.length() > 0) denied.append(", ");
                            denied.append(entry.getKey());
                        }
                    }
                    if (!allGranted) {
                        Log.w("ChatActivity", "Mesh permissions denied: " + denied);
                        View __root = findViewById(R.id.root);
                        if (__root != null) {
                            Snackbar.make(__root,
                                    "Bluetooth & Wi-Fi permissions needed for Mesh mode",
                                    Snackbar.LENGTH_LONG).show();
                        }
                    } else {
                        Log.i("ChatActivity", "All Bluetooth/Wi-Fi mesh permissions granted");
                        // Re-fire transport mode now that permissions are confirmed
                        onTransportModeChanged(TransportMode.SOSBLUE_MESH);
                    }
                });

        // ── Runtime permission launcher for WRITE_EXTERNAL_STORAGE ────
        // Only needed on Android 9 and below (API < 29) for saving media
        // to the device gallery. On Android 10+, MediaStore is used instead
        // and no storage permission is required.
        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted && pendingDownloadMessage != null) {
                        saveMediaToGallery(pendingDownloadMessage);
                    } else if (!granted) {
                        ToastUtils.showShort(this,
                                getString(R.string.download_permission_required));
                    }
                    pendingDownloadMessage = null;
                });

        // ── Runtime permission launcher for SMS carrier ──────────────
        // SEND_SMS + RECEIVE_SMS are both runtime-dangerous on API 23+
        // (and Play Protect flags them as a sensitive pair). READ_PHONE_STATE
        // gates `TelephonyManager.getSubscriberId()` so we can detect SIM
        // presence. We request all three at once so the user only sees one
        // dialog; if they deny any of the first two we revert the radio.
        smsPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    boolean sendGranted = Boolean.TRUE.equals(
                            result.get(Manifest.permission.SEND_SMS));
                    boolean receiveGranted = Boolean.TRUE.equals(
                            result.get(Manifest.permission.RECEIVE_SMS));
                    boolean phoneGranted = Boolean.TRUE.equals(
                            result.get(Manifest.permission.READ_PHONE_STATE));
                    boolean readGranted = Boolean.TRUE.equals(
                            result.get(Manifest.permission.READ_SMS));
                    if (sendGranted && receiveGranted) {
                        Log.i("ChatActivity",
                                "SMS permissions granted (phone=" + phoneGranted
                                        + ", read=" + readGranted + ")");
                        // ── Force-register the SMS receiver (may have failed
                        //    earlier when RECEIVE_SMS was not yet granted).
                        //    We do this DIRECTLY instead of re-firing
                        //    onTransportModeChanged() because the mode guard
                        //    (mode == currentTransportMode) would block the
                        //    call — and the receiver would stay null forever.
                        com.antor.sosblue.bridge.SmsTransport smsForReg =
                                pendingSmsTransport != null
                                        ? pendingSmsTransport
                                        : (bridge != null ? bridge.getSmsTransport() : null);
                        if (smsForReg != null) {
                            smsForReg.register();
                        }
                        // ── Kick a fresh inbox scan now that READ_SMS may have
                        //    just been granted; previously-delivered DR1 envelopes
                        //    will surface in chat within a second.
                        if (readGranted && bridge != null) {
                            com.antor.sosblue.bridge.SmsTransport smsT =
                                    smsForReg != null ? smsForReg : bridge.getSmsTransport();
                            if (smsT != null) smsT.scanInbox();
                        }
                    } else {
                        Log.w("ChatActivity",
                                "SMS permissions denied (send=" + sendGranted
                                        + ", receive=" + receiveGranted
                                        + ", phone=" + phoneGranted
                                        + ", read=" + readGranted + ")");
                        View __root = findViewById(R.id.root);
                        if (__root != null) {
                            Snackbar.make(__root,
                                    getString(R.string.transport_sms_permission_required),
                                    Snackbar.LENGTH_LONG).show();
                        }
                        revertRadioToLastSaved();
                    }
                });

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat);

        // ── Request runtime Wi-Fi/location permissions (Android 10-12) ──
        // On Android 10-12, ACCESS_FINE_LOCATION is required for Wi-Fi
        // scanning and Wi-Fi Direct discovery. On Android 13+, the
        // NEARBY_WIFI_DEVICES permission is auto-granted. Oppo ColorOS
        // on Android 11 crashes if this is not granted at runtime.
        requestWifiPermissionsIfNeeded();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // Apply system-bar padding (status bar) on top,
            // and IME (keyboard) inset on bottom so the input bar
            // always floats above the soft keyboard.
            v.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });

        // ---------------------------------------------------------------
        //  Initialise F2P Bridge (engine starts later on demand)
        // ---------------------------------------------------------------

        bridge = new F2PBridge(this);

        // ── Request SMS runtime permissions eagerly ───────────────────
        // READ_SMS in particular is needed by SmsTransport.scanInbox()
        // BEFORE the first scan runs, so the inbox pull on activity
        // start can read com.android.providers.telephony. Without this,
        // the scanner logs "Permission Denial" and silently skips every
        // DR1 envelope in the inbox. SEND/RECEIVE_SMS also gate the
        // outbound + live-broadcast paths, so we request them here
        // once instead of waiting until the user taps SMS Relay.
        requestSmsPermissions();

        // ── Register local user identity in the display-name cache ──
        // This ensures the user's own name is known, and also sets a
        // precedent for populating known-discovered peers.
        String myKnownPhone = UserIdentity.getPhoneNumber(this);
        String myKnownName = UserIdentity.getUsername(this);
        if (myKnownPhone != null && myKnownName != null) {
            com.antor.sosblue.notification.NotificationHelper
                    .registerDisplayName(myKnownPhone, myKnownName);
        }

        // ── Register peer discovery listener ────────────────────────
        // Populates the NotificationHelper's phone→display-name cache
        // so incoming message notifications show the sender's username
        // instead of a raw phone number.
        bridge.addPeerDiscoveryListener(new F2PBridge.PeerDiscoveryListener() {
            @Override
            public void onPeerDiscovered(String nodeId, String username,
                                          String phone, String ipAddress, int port) {
                if (phone != null && username != null && !username.isEmpty()) {
                    com.antor.sosblue.notification.NotificationHelper
                            .registerDisplayName(phone, username);
                }
            }

            @Override
            public void onPeerLost(String nodeId) {
                // No-op: we keep the cached name even after peer disappears
            }
        });

        // ── Register SMS error listener ─────────────────────────────
        // Surfaces synchronous SMS send failures (no telephony, no
        // sender phone, SecurityException) via Snackbar so the user
        // gets feedback without reading logcat.
        bridge.addSmsErrorListener(reason -> showSmsError(reason));

        // ── Register incoming message handler ────────────────────────
        // Listen on onPacketReceived (NOT onSignal) because incoming
        // messages from remote nodes arrive as packets via notifyPacket().
        bridge.registerListener(new EngineCallback() {
            @Override
            public void onSignal(com.antor.f2p.engine.api.FibreSignal signal) {
                // Locally-dispatched signals: no-op for incoming
            }

            @Override
            public void onPacketReceived(FibrePacket packet) {
                try {
                    String myPhoneRaw = UserIdentity.getPhoneNumber(ChatActivity.this);
                    if (myPhoneRaw == null) return;

                    // Normalize our own phone number for consistent address matching
                    String myPhone = UserIdentity.normalizePhoneNumber(myPhoneRaw);

                    String payloadStr = new String(packet.getRawDataBuffer(),
                            java.nio.charset.StandardCharsets.UTF_8);

                    Log.d("ChatActivity", "Incoming packet payload: "
                            + payloadStr.substring(0, Math.min(payloadStr.length(), 200)));

                    // Check if this is an F2P targeted message
                    if (!payloadStr.contains("recipient_phone")) return;

                    // Extract fields from the JSON payload
                    String recipientPhone = UserIdentity.normalizePhoneNumber(
                            JsonPayloadHelper.extractField(payloadStr, "recipient_phone"));
                    String senderPhone = UserIdentity.normalizePhoneNumber(
                            JsonPayloadHelper.extractField(payloadStr, "sender_phone"));
                    String envelopeB64 = JsonPayloadHelper.extractField(payloadStr, "f2p_envelope");

                    // ── Address matching with logging ────────────────
                    // Only deliver to ourselves (normalized comparison)
                    if (!myPhone.equals(recipientPhone)) {
                        Log.d("ChatActivity", "Packet recipient mismatch: myPhone="
                                + myPhone + ", packet.recipient=" + recipientPhone
                                + " — dropping");
                        return;
                    }
                    Log.d("ChatActivity", "Packet matched local phone=" + myPhone
                            + ", sender=" + senderPhone);
                    if (senderPhone == null || envelopeB64 == null) return;

                    // Decrypt the F2P envelope
                    // CRITICAL: decrypt with MY phone (recipientPhone from the message),
                    // because the sender encrypted using the recipient's phone-derived key.
                    byte[] envelopeBytes = Base64.decode(envelopeB64, Base64.NO_WRAP);
                    F2PMessage f2pMsg = F2PMessage.deserialize(envelopeBytes);
                    byte[] decryptedBytes = MessageEncryptor.decrypt(
                            myPhone, f2pMsg.getEncryptedPayload());

                    // ── Check if this is a media chunk ─────────────────
                    String signalType = JsonPayloadHelper.extractField(payloadStr, "transport");
                    String transferId = JsonPayloadHelper.extractField(payloadStr, "transfer_id");

                    if (transferId != null) {
                        // This is a media chunk — reassemble it
                        handleMediaChunk(senderPhone, myPhone, transferId, payloadStr, decryptedBytes);
                        return;
                    }

                    // ── Plain text message ────────────────────────────
                    String decryptedText = new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
                    final String sender = senderPhone;
                    final String text = decryptedText;
                    final boolean isMeshBroadcast = "mesh".equals(signalType);
                    runOnUiThread(() -> {
                        int inboundTransportCode = isMeshBroadcast
                                ? MessageModel.TRANSPORT_MESH
                                : MessageModel.TRANSPORT_F2P;
                        MessageModel inbound = new MessageModel(text, false, sender, myPhone,
                                inboundTransportCode, MessageModel.STATUS_DELIVERED);
                        allMessages.add(inbound);
                        addMessage(inbound);

                        // ── Register in conversation inbox ──────────────────
                        String resolvedName = com.antor.sosblue.notification.NotificationHelper
                                .lookupDisplayName(sender);
                        ConversationRegistry.update(
                                sender,
                                resolvedName,
                                text,
                                System.currentTimeMillis(),
                                false,  // isOutgoing
                                false,  // hasMedia
                                true,   // incrementUnread (incoming message)
                                isMeshBroadcast ? "SOSBLUE_MESH" : "F2P_SERVERLESS"
                        );

                        // ── Post notification if activity is not in foreground ──
                        // Mesh-broadcast messages are treated as group conversations
                        // (multi-sender), while F2P/SMS messages are 1:1.
                        String localPhone = UserIdentity.getPhoneNumber(ChatActivity.this);
                        if (localPhone != null && !localPhone.equals(sender)) {
                            com.antor.sosblue.notification.NotificationHelper nh =
                                    new com.antor.sosblue.notification.NotificationHelper(ChatActivity.this);
                            if (isMeshBroadcast) {
                                // Group notification with per-sender attribution
                                nh.notifyGroupMessage(
                                        "offline36_mesh_broadcast",   // groupId
                                        "Offline-36 Mesh",             // groupName
                                        sender,                     // senderPhone
                                        sender,                     // senderName (lookupDisplayName resolves it)
                                        text);
                            } else {
                                // 1:1 conversation notification
                                nh.notifyIncomingMessage(sender, text);
                            }
                        }
                    });
                } catch (Exception e) {
                    android.util.Log.e("ChatActivity", "Failed to process incoming F2P message", e);
                    if (!isActivityDestroyed) {
                        runOnUiThread(() -> {
                            if (isActivityDestroyed) return;
                            String senderInfo = "unknown sender";
                            try {
                                String payloadStr2 = new String(packet.getRawDataBuffer(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                                String sender2 = JsonPayloadHelper.extractField(payloadStr2, "sender_phone");
                                if (sender2 != null) senderInfo = sender2;
                            } catch (Exception ignored) {}
                            View root = findViewById(R.id.root);
                            if (root == null) return;
                            Snackbar.make(root,
                                    "⚠ Could not decrypt message from " + senderInfo,
                                    Snackbar.LENGTH_LONG).show();
                        });
                    }
                }
            }
        });

        // ── SMS receive hook ───────────────────────────────────────────
        // Eagerly create the SmsTransport so the receiver is registered
        // for the lifetime of this Activity. Listeners attached here
        // receive every inbound F2P-envelope-bearing SMS and inject
        // decrypted text into the same chat list as mesh/F2P messages.
        com.antor.sosblue.bridge.SmsTransport sms = bridge.getSmsTransport();
        if (sms == null) {
            sms = new com.antor.sosblue.bridge.SmsTransport(this);
            // Mirror the bridge's lazy allocation by reflection would be
            // overkill — instead, prompt an init by calling isAvailable
            // (no-op besides the telephony check), then proceed. The
            // bridge will reuse this same instance on the first SMS send.
            sms.register();
            pendingSmsTransport = sms;
        } else {
            sms.register();
        }
        smsEnvelopeListener = new com.antor.sosblue.bridge.SmsTransport.OnSmsEnvelopeListener() {
            @Override
            public void onEnvelope(String senderPhone, String recipientPhone, byte[] encryptedPayload) {
                try {
                    String myPhoneRaw = UserIdentity.getPhoneNumber(ChatActivity.this);
                    if (myPhoneRaw == null) return;
                    String myPhone = UserIdentity.normalizePhoneNumber(myPhoneRaw);
                    // Normalize recipientPhone too so the comparison is robust
                    // even if the sender stored a differently-formatted number.
                    String normalizedRecipient = UserIdentity.normalizePhoneNumber(recipientPhone);
                    if (normalizedRecipient == null || !myPhone.equals(normalizedRecipient)) {
                        Log.d("ChatActivity", "SMS envelope not addressed to us (to="
                                + recipientPhone + "), dropping");
                        return;
                    }
                    if (senderPhone == null || encryptedPayload == null) return;

                    byte[] decryptedBytes = MessageEncryptor.decrypt(myPhone, encryptedPayload);

                    // ── Check if this is an SMS media chunk ───────────────
                    com.antor.sosblue.bridge.SmsMediaHelper.DecodedChunk smsMediaChunk =
                            com.antor.sosblue.bridge.SmsMediaHelper.tryDecode(decryptedBytes);
                    if (smsMediaChunk != null) {
                        // Route to media chunk reassembly
                        handleSmsMediaChunk(senderPhone, myPhone, smsMediaChunk);
                        return;
                    }

                    // ── Regular text message ────────────────────────────
                    String text = new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
                    String sender = UserIdentity.normalizePhoneNumber(senderPhone);
                    final String finalSender = sender != null ? sender : senderPhone;
                    final String finalText = text;
                    runOnUiThread(() -> {
MessageModel inbound = new MessageModel(finalText, false, finalSender, myPhone,
                                MessageModel.TRANSPORT_SMS, MessageModel.STATUS_DELIVERED);
                        allMessages.add(inbound);
                        // Only inject into the open conversation's adapter if
                        // the user is currently viewing that thread. If the
                        // user launched the activity without a recipient
                        // extra (the default fallback is the user's own
                        // phone, e.g. +8801554331776), an inbound message
                        // from a different sender still needs to land in
                        // *its own* chat file — we can't just blindly
                        // persist it under the open chat's key.
                        if (finalSender.equals(currentRecipientPhone)
                                || finalSender.equals(UserIdentity.normalizePhoneNumber(
                                        UserIdentity.getPhoneNumber(ChatActivity.this)))) {
                            addMessage(inbound);
                        }

                        // ── Persist under the sender's own chat file ─────────
                        // This way a DR1 envelope always lands in the
                        // sender-keyed history regardless of which
                        // conversation is currently open. The in-memory
                        // adapter update above handles the live-view case.
                        if (historyStore != null && finalSender != null
                                && !finalSender.isEmpty()) {
                            final String senderKey = finalSender;
                            historyStore.loadAsync(senderKey,
                                    (phone, msgs) -> {
                                        java.util.List<MessageModel> updated =
                                                new java.util.ArrayList<>(msgs);
                                        updated.add(inbound);
                                        historyStore.saveAsync(senderKey, updated, null);
                                    });
                        }

                        // ── Register in conversation inbox ──────────────────
                        String smsResolved = com.antor.sosblue.notification.NotificationHelper
                                .lookupDisplayName(finalSender);
                        ConversationRegistry.update(
                                finalSender,
                                smsResolved,
                                finalText,
                                System.currentTimeMillis(),
                                false,  // isOutgoing
                                false,  // hasMedia
                                true,   // incrementUnread
                                "SMS_FALLBACK"
                        );
                    });
                } catch (Exception e) {
                    android.util.Log.e("ChatActivity", "Failed to decrypt inbound SMS F2P envelope", e);
                }
            }
        };
        sms.addEnvelopeListener(smsEnvelopeListener);

        // Pull any DR1 envelopes already in the inbox (self-sent SMSs
        // are recorded by the provider but never re-broadcast to our
        // process). Gated on READ_SMS so the call is a no-op before
        // the user grants the runtime permission. The listener is now
        // attached, so any decoded envelopes will fan out into the chat
        // list and the conversation registry.
        if (sms != null
                && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.READ_SMS)
                        == PackageManager.PERMISSION_GRANTED) {
            sms.scanInbox();
        }

        // ---------------------------------------------------------------
        //  Bind views
        // ---------------------------------------------------------------

        transportRadioGroup = findViewById(R.id.transportRadioGroup);
        bufferingProgress = findViewById(R.id.bufferingProgress);
        loadingContainer = findViewById(R.id.loadingContainer);
        sendButton = findViewById(R.id.sendButton);
        peerBarCard = findViewById(R.id.peerBarCard);
        peerBarRecyclerView = findViewById(R.id.peerBarRecyclerView);
        peerCountBadge = findViewById(R.id.peerCountBadge);
        inputRecipientPhone = findViewById(R.id.inputRecipientPhone);
        encryptionBadge = findViewById(R.id.encryptionBadge);
        searchInput = findViewById(R.id.searchInput);

        // ── Download progress views ───────────────────────────────
        downloadProgressContainer = findViewById(R.id.downloadProgressContainer);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        downloadProgressText = findViewById(R.id.downloadProgressText);

        // ── Recipient phone → E2E badge visibility ───────────────
        // Show the red "E2E" badge whenever the user has typed a valid
        // E.164 recipient phone number, indicating end-to-end encryption
        // is active. Uses MessageEncryptor.isValidE164() for strict validation.
        inputRecipientPhone.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String phone = s.toString().trim();
                boolean valid = MessageEncryptor.isValidE164(phone);
                encryptionBadge.setVisibility(valid ? View.VISIBLE : View.GONE);

                // ── Chat history swap ──────────────────────────────────────
                // When the user switches to a different recipient phone we
                // flush the current thread to disk and load the new one.
                String normalized = UserIdentity.normalizePhoneNumber(phone);
                if (normalized == null) normalized = "";
                if (!normalized.equals(currentRecipientPhone)) {
                    // Save outgoing thread first (if any), then switch.
                    persistHistory();
                    currentRecipientPhone = normalized;
                    loadHistoryFor(currentRecipientPhone);
                }
            }
        });

        // Pre-fill recipient from Intent extra, or fall back to own phone
        String recipientFromIntent = getIntent().getStringExtra(EXTRA_RECIPIENT_PHONE);
        String nameFromIntent = getIntent().getStringExtra(EXTRA_RECIPIENT_NAME);
        if (recipientFromIntent != null && !recipientFromIntent.isEmpty()) {
            inputRecipientPhone.setText(recipientFromIntent);
            String displayName = (nameFromIntent != null) ? nameFromIntent : recipientFromIntent;
            // Update the title to show the chat partner's name
            TextView titleView = findViewById(R.id.appTitle);
            if (titleView != null) {
                titleView.setText(displayName);
            } else {
                // Fallback: title defaults to "Offline-36" in layout
            }
            // Mark conversation as read when opened from inbox
            ConversationRegistry.markRead(recipientFromIntent);
            ToastUtils.showShort(this, "Chat with " + displayName);
        } else {
            // Fall back to the user's own phone (for self-testing)
            String myPhone = UserIdentity.getPhoneNumber(this);
            if (myPhone != null && inputRecipientPhone.getText().toString().isEmpty()) {
                inputRecipientPhone.setText(myPhone);
            }
        }

        // ---------------------------------------------------------------
        //  Chat RecyclerView
        // ---------------------------------------------------------------

        RecyclerView chatList = findViewById(R.id.chatRecyclerView);
        chatAdapter = new ChatAdapter();

        // ── Download button handler for incoming media ──────────────
        // When the user taps the download icon on an incoming image/video,
        // show a confirmation dialog and save to device gallery via MediaStore.
        chatAdapter.setOnDownloadClickListener(this::showDownloadMediaDialog);

        chatList.setLayoutManager(new LinearLayoutManager(this));
        chatList.setAdapter(chatAdapter);

        // ── Per-recipient chat history ───────────────────────────────────────
        historyStore = new ChatHistoryStore(this);
        // Resolve the initial recipient up-front so cold-start loads the right
        // thread instead of showing an empty chat.
        String initialRecipientRaw = inputRecipientPhone.getText().toString().trim();
        String initialRecipient = UserIdentity.normalizePhoneNumber(initialRecipientRaw);
        currentRecipientPhone = initialRecipient != null ? initialRecipient : "";
        if (!currentRecipientPhone.isEmpty()) {
            final String key = currentRecipientPhone;
            historyStore.loadAsync(key, (phone, messages) -> {
                // Only apply if the recipient hasn't changed while we were
                // reading disk.
                if (phone.equals(currentRecipientPhone) && !messages.isEmpty()) {
                    chatAdapter.submitList(new ArrayList<>(messages));
                    chatList.scrollToPosition(chatAdapter.getItemCount() - 1);
                }
            });
        }

        // ---------------------------------------------------------------
        //  Peer bar adapter (compact inline list in the CardView)
        //  NOTE: Must be initialised BEFORE onTransportModeChanged()
        //  because that method calls refreshPeerBar() which uses this
        //  adapter.  Previously this was below the transport-mode call
        //  which caused a NullPointerException on cold start.
        // ---------------------------------------------------------------

        peerBarAdapter = new PeerDiscoveryAdapter(new ArrayList<>(), peer -> {
            ToastUtils.showShort(this, "Chat with " + peer.getName());
        });
        peerBarRecyclerView.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        peerBarRecyclerView.setAdapter(peerBarAdapter);

        // ---------------------------------------------------------------
        //  Transport selector — persist mode preference
        // ---------------------------------------------------------------

        TransportMode savedMode = TransportMode.load(this);
        if (savedMode != null) {
            // Restore the user's last-selected transport mode
            switch (savedMode) {
                case F2P_SERVERLESS:
                    transportRadioGroup.check(R.id.rb_f2p_serverless);
                    break;
                case SMS_FALLBACK:
                    transportRadioGroup.check(R.id.rb_sms_fallback);
                    break;
                case SOSBLUE_MESH:
                    transportRadioGroup.check(R.id.rb_sosblue_mesh);
                    break;
            }
            // TransportMode.load() now defaults to F2P, so all cases covered
        } else {
            // No saved mode — default to F2P (first button, checked in layout)
            transportRadioGroup.check(R.id.rb_f2p_serverless);
        }

        transportRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            TransportMode mode = radioIdToTransportMode(checkedId);
            // ── Guard: defer if we're in the middle of a mode switch ──
            // Instead of silently dropping the tap, we store it as
            // pendingMode so it gets processed immediately after the
            // current transition completes.
            if (isModeSwitching) {
                Log.d("ChatActivity", "Mode switch in progress, deferring tap to " + mode);
                pendingMode = mode;
                return;
            }
            // ── Guard: skip if mode hasn't actually changed ──
            if (mode == currentTransportMode) {
                return;
            }
            // ── SMS gate: runtime permission request on first selection ──
            // SEND_SMS + RECEIVE_SMS + READ_PHONE_STATE are all dangerous,
            // so the OS won't surface them at install time — we have to
            // ask here. If the device has no SIM/telephony at all, we
            // short-circuit with a Snackbar and revert to F2P.
            if (mode == TransportMode.SMS_FALLBACK) {
                if (!TransportMode.SMS_FALLBACK.isAvailable(this)) {
                    Snackbar.make(findViewById(R.id.root),
                            getString(R.string.transport_sms_unavailable),
                            Snackbar.LENGTH_LONG).show();
                    revertRadioToLastSaved();
                    return;
                }
                if (!hasAllSmsPermissions()) {
                    requestSmsPermissions();
                    // Persist now so a permission deny leaves the user's
                    // last successful choice intact. If they ultimately
                    // grant, the radio stays as-is; if they deny, the
                    // permission callback reverts the radio.
                    mode.save(this);
                    onTransportModeChanged(mode);
                    return;
                }
            }
            mode.save(this);
            onTransportModeChanged(mode);
        });

        // Fire handler for the initial mode
        onTransportModeChanged(
                radioIdToTransportMode(transportRadioGroup.getCheckedRadioButtonId()));

        // ---------------------------------------------------------------
        //  Top action bar buttons
        // ---------------------------------------------------------------

        // Search icon → Toggle smooth expanding search bar overlay
        findViewById(R.id.searchIcon).setOnClickListener(v -> toggleSearchBar());
        findViewById(R.id.searchCloseIcon).setOnClickListener(v -> hideSearchBar());

        // Search input listener for filtering messages
        searchInput = findViewById(R.id.searchInput);
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String query = s.toString().toLowerCase(java.util.Locale.ROOT).trim();
                // Filter visible messages — we iterate the current list
                // and update visibility. Since we use ListAdapter with
                // submitList, we re-submit with the filtered set.
                java.util.List<MessageModel> current = new ArrayList<>(chatAdapter.getCurrentList());
                if (query.isEmpty()) {
                    chatAdapter.submitList(new ArrayList<>(allMessages));
                } else {
                    java.util.List<MessageModel> filtered = new ArrayList<>();
                    for (MessageModel msg : allMessages) {
                        if (msg.getText().toLowerCase(java.util.Locale.ROOT).contains(query)
                                || (msg.getSenderPhone() != null
                                    && msg.getSenderPhone().toLowerCase(java.util.Locale.ROOT).contains(query))) {
                            filtered.add(msg);
                        }
                    }
                    chatAdapter.submitList(filtered);
                }
            }
        });

        // Broadcast (RSS) icon → Open NewsFeedActivity
        findViewById(R.id.discoverIcon).setOnClickListener(v -> {
            Intent newsIntent = new Intent(ChatActivity.this,
                    com.antor.sosblue.news.NewsFeedActivity.class);
            startActivity(newsIntent);
        });

        // Overflow menu (3 dots) → PopupMenu with options (including Nearby Devices)
        findViewById(R.id.threeDotIcon).setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(ChatActivity.this, v);
            popup.getMenuInflater().inflate(R.menu.top_app_bar_menu, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.menu_about) {
                    showAboutDialog();
                    return true;
                } else if (id == R.id.menu_settings) {
                    startActivity(new Intent(ChatActivity.this,
                            com.antor.sosblue.settings.SettingsActivity.class));
                    return true;
                } else if (id == R.id.menu_nearby) {
                    // Toggle the peer bar (previously handled by the inline title)
                    if (peerBarCard.getVisibility() == View.VISIBLE) {
                        peerBarCard.setVisibility(View.GONE);
                    } else {
                        refreshPeerBar();
                        peerBarCard.setVisibility(View.VISIBLE);
                    }
                    return true;
                }
                return false;
            });
            popup.show();
        });

        // Title container removed — Nearby Devices action moved into overflow menu

        // ---------------------------------------------------------------
        //  Attachment button → media picker (images + videos)
        // ---------------------------------------------------------------

        // Register the system photo/video picker using OpenDocument.
        // OpenDocument supports multiple MIME types and grants persistent
        // URI access (no READ_MEDIA permission needed on Android 13+).
        mediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        // Take persistent read permission so the URI survives
                        // beyond this activity's lifecycle
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {}
                        onMediaSelected(uri);
                    }
                });

        findViewById(R.id.attachmentButton).setOnClickListener(v -> {
            // Open the system picker for both images AND videos
            mediaPickerLauncher.launch(new String[]{
                    "image/*",
                    "video/*"
            });
        });

        // ---------------------------------------------------------------
        //  Send button + IME action
        // ---------------------------------------------------------------

        findViewById(R.id.sendButton).setOnClickListener(v -> sendCurrentMessage());

        EditText inputMessage = findViewById(R.id.inputMessage);
        inputMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage();
                return true;
            }
            return false;
        });
    }

    private volatile boolean isActivityDestroyed = false;

    @Override
    protected void onStop() {
        super.onStop();
        // Activity going to background — resources preserved for potential resume,
        // full cleanup happens in onDestroy
    }

    @Override
    protected void onPause() {
        // Flush the current thread to disk before the activity goes away.
        persistHistory();
        loadHistoryFor(currentRecipientPhone); // no-op if same; re-fetches if swap
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        isActivityDestroyed = true;
        try {
            if (bridge != null) {
                bridge.stopEngine();
                bridge = null;
            }
        } catch (Exception e) {
            Log.w("ChatActivity", "Error stopping engine in onDestroy", e);
        }
        try {
            if (pendingSmsTransport != null) {
                if (smsEnvelopeListener != null) {
                    pendingSmsTransport.removeEnvelopeListener(smsEnvelopeListener);
                }
                pendingSmsTransport.unregister();
                pendingSmsTransport.shutdown();
                pendingSmsTransport = null;
            }
        } catch (Exception e) {
            Log.w("ChatActivity", "Error cleaning up SMS transport in onDestroy", e);
        }
        smsEnvelopeListener = null;
        if (historyStore != null) {
            historyStore.shutdown();
            historyStore = null;
        }
        // Dismiss the mode-switch dialog if it's still showing
        // (catches edge cases like activity destruction mid-transition).
        dismissModeSwitchDialog(false);
        super.onDestroy();
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static TransportMode radioIdToTransportMode(int radioId) {
        if (radioId == R.id.rb_f2p_serverless) {
            return TransportMode.F2P_SERVERLESS;
        }
        if (radioId == R.id.rb_sms_fallback) {
            return TransportMode.SMS_FALLBACK;
        }
        return TransportMode.SOSBLUE_MESH;
    }

    // ---------------------------------------------------------------
    //  Chat history helpers
    // ---------------------------------------------------------------

    /**
     * Append {@code msg} to the visible list and persist the new thread.
     * Centralises the "snapshot + submitList + save" pattern so every
     * message path (text send, media send, F2P inbound, SMS inbound)
     * goes through the same code and the persistence layer can never
     * drift out of sync with the adapter.
     */
    private void addMessage(@NonNull MessageModel msg) {
        java.util.List<MessageModel> updated = new ArrayList<>(chatAdapter.getCurrentList());
        updated.add(msg);
        chatAdapter.submitList(updated);
        persistHistory();
    }

    /**
     * Snapshot the current adapter list and write it to disk for the
     * currently-active recipient.  No-op if no recipient is set yet
     * (we don't want to remember orphan messages under an empty key).
     */
    private void persistHistory() {
        if (historyStore == null) return;
        if (currentRecipientPhone == null || currentRecipientPhone.isEmpty()) return;
        if (chatAdapter == null) return;
        final String phone = currentRecipientPhone;
        final java.util.List<MessageModel> snapshot =
                new ArrayList<>(chatAdapter.getCurrentList());
        historyStore.saveAsync(phone, snapshot, null);
    }

    /**
     * Load history for {@code phone} from disk and replace the visible
     * adapter list. Pass-through if {@code phone} is empty / null.
     */
    private void loadHistoryFor(@Nullable String phone) {
        if (historyStore == null) return;
        if (phone == null || phone.isEmpty()) {
            chatAdapter.submitList(new ArrayList<>());
            return;
        }
        final String key = phone;
        historyStore.loadAsync(key, (loadedPhone, messages) -> {
            // Only swap if the user hasn't switched to another thread
            // while we were reading from disk.
            if (!loadedPhone.equals(currentRecipientPhone)) return;
            chatAdapter.submitList(new ArrayList<>(messages));
        });
    }

    private void showSendProgress(boolean show) {
        if (loadingContainer != null) {
            loadingContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (sendButton != null) {
            sendButton.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------
    //  Transport mode switching
    // ---------------------------------------------------------------

    private void onTransportModeChanged(TransportMode mode) {
        // ── Guard: prevent re-entrant calls during an active transition ──
        // Rapid taps are now queued via pendingMode (set in the
        // OnCheckedChangeListener) and processed after dismissModeSwitchDialog
        // resets isModeSwitching. The guard is still needed here to prevent
        // the OnCheckedChangeListener from re-entering onTransportModeChanged
        // while we are synchronously completing a Mesh or SMS switch.
        if (isModeSwitching) {
            Log.d("ChatActivity", "Mode switch already in progress, deferring " + mode);
            return;
        }
        // ── Guard: no-op if mode hasn't actually changed ──
        if (mode == currentTransportMode) {
            return;
        }
        isModeSwitching = true;
        final TransportMode previousMode = currentTransportMode;
        currentTransportMode = mode;

        try {
            // ── Show non-cancelable buffering dialog for ALL transitions ──
            showModeSwitchDialog();
            // ── Safety timeout: auto-dismiss after 1.5 seconds ──
            modeSwitchTimeoutRunnable = () -> {
                if (isModeSwitching) {
                    Log.w("ChatActivity", "Mode switch timed out after 1.5s for " + mode);
                    dismissModeSwitchDialog(false);
                    View __root = findViewById(R.id.root);
                    if (__root != null) {
                        Snackbar.make(__root,
                                "Mode switch timed out for " + mode.getLabel(),
                                Snackbar.LENGTH_SHORT).show();
                    }
                }
            };
            modeSwitchTimeoutHandler.postDelayed(modeSwitchTimeoutRunnable, 3000);

            // ── Stop F2P engine if we are LEAVING F2P mode ──
            // This prevents the engine from running in the background
            // when the user switches to Mesh or SMS, freeing resources
            // and avoiding stale socket state.
            if (previousMode == TransportMode.F2P_SERVERLESS && engineReady) {
                Log.i("ChatActivity", "Stopping F2P engine on mode switch to " + mode);
                try {
                    if (bridge != null) {
                        bridge.stopEngine();
                    }
                } catch (Exception e) {
                    Log.w("ChatActivity", "Error stopping engine during mode switch", e);
                }
                engineReady = false;
                f2pRequested = false;
            }

            if (mode == TransportMode.SOSBLUE_MESH) {
                // ── Mesh mode ────────────────────────────────────────────
                // Request Bluetooth + Wi-Fi runtime permissions
                requestMeshPermissionsIfNeeded();

                // Show peer bar with nearby devices
                if (peerBarCard != null) {
                    peerBarCard.setVisibility(View.VISIBLE);
                }
                refreshPeerBar();

                dismissModeSwitchDialog(true);

            } else if (mode == TransportMode.SMS_FALLBACK) {
                // ── SMS carrier path ─────────────────────────────────────
                if (peerBarCard != null) {
                    peerBarCard.setVisibility(View.GONE);
                }

                // Ensure SMS transport is available
                if (bridge != null) {
                    com.antor.sosblue.bridge.SmsTransport sms = bridge.getSmsTransport();
                    if (sms == null) {
                        try {
                            sms = new com.antor.sosblue.bridge.SmsTransport(this);
                            bridge.setSmsTransport(sms);
                        } catch (Exception e) {
                            Log.w("ChatActivity", "Failed to create SMS transport", e);
                        }
                    }
                    if (sms != null) {
                        try {
                            sms.register();
                            // Pull any DR1 envelopes that landed in the inbox
                            // before this Activity opened (self-sent messages
                            // arrive via the SMS provider, not the broadcast).
                            if (ContextCompat.checkSelfPermission(this,
                                    Manifest.permission.READ_SMS)
                                    == PackageManager.PERMISSION_GRANTED) {
                                sms.scanInbox();
                            }
                        } catch (Exception e) {
                            Log.w("ChatActivity", "Failed to register SMS transport", e);
                        }
                    }
                    pendingSmsTransport = sms;
                }

                dismissModeSwitchDialog(true);
                ToastUtils.showShort(this, "SMS Relay ready");

            } else if (mode == TransportMode.F2P_SERVERLESS) {
                // ── F2P Serverless ───────────────────────────────────────
                if (peerBarCard != null) {
                    peerBarCard.setVisibility(View.GONE);
                }
                f2pRequested = true;

                if (!engineReady) {
                    // Dialog is already showing from the top of onTransportModeChanged

                    // ── F2P: Use phone number as node ID ──
                    String myPhone = UserIdentity.getPhoneNumber(ChatActivity.this);
                    if (myPhone == null) {
                        myPhone = "offline36-" + System.currentTimeMillis();
                    }
                    EngineConfig config = EngineConfig.builder()
                            .nodeId(myPhone)
                            .build();

                    if (bridge != null) {
                        // ── Mark that we're awaiting async engine start ──
                        // This flag prevents the finally block from dismissing
                        // the mode-switch dialog prematurely. It is cleared
                        // in both onEngineStarted() and onEngineError().
                        awaitingF2pEngine = true;
                        bridge.startEngineAsync(config, new F2PBridge.OnEngineStartListener() {
                            @Override
                            public void onEngineStarted() {
                                engineReady = true;
                                awaitingF2pEngine = false;
                                dismissModeSwitchDialog(true);
                                refreshPeerBar();
                                ToastUtils.showShort(ChatActivity.this, "F2P Serverless ready");
                            }

                            @Override
                            public void onEngineError(int statusCode, String message) {
                                awaitingF2pEngine = false;
                                dismissModeSwitchDialog(false);
                                View __root = findViewById(R.id.root);
                                if (__root != null) {
                                    Snackbar.make(__root,
                                            "F2P engine error: " + message,
                                            Snackbar.LENGTH_LONG).show();
                                }
                            }
                        });
                    } else {
                        // Bridge is null — shouldn't happen, but be safe
                        dismissModeSwitchDialog(false);
                        Log.e("ChatActivity", "Cannot start F2P engine: bridge is null");
                    }
                } else {
                    // Engine already running
                    dismissModeSwitchDialog(true);
                }
            }
        } catch (Exception e) {
            Log.e("ChatActivity", "Error during mode switch to " + mode, e);
            dismissModeSwitchDialog(false);
            View __root = findViewById(R.id.root);
            if (__root != null) {
                Snackbar.make(__root,
                        "Mode switch failed: " + e.getMessage(),
                        Snackbar.LENGTH_LONG).show();
            }
            // Revert to previous mode tracking on error
            currentTransportMode = previousMode;
        } finally {
            // NOTE: isModeSwitching is NOT reset here because the F2P
            // async engine path needs it to stay true until the engine
            // actually starts (or times out after 3s). It is reset
            // inside dismissModeSwitchDialog() which is called on every
            // success / error / timeout path.
            //
            // Safety net: if a RuntimeException slips through the
            // catch block and leaves the dialog showing, dismiss it
            // here — BUT ONLY if we are NOT waiting for the async F2P
            // engine to start. If awaitingF2pEngine is true, the dialog
            // stays up intentionally and will be dismissed by the
            // onEngineStarted / onEngineError callback.
            //
            // This guards against the following crash sequence:
            //   1. User taps F2P -> startEngineAsync() queued
            //   2. finally block runs, dialog is showing
            //   3. WITHOUT this guard, dialog dismissed, isModeSwitching=false
            //   4. User taps SMS again -> new onTransportModeChanged() fires
            //   5. Second engine start queues while first is still running
            //   6. Socket conflicts -> engine crash -> ChatActivity finishes
            //   7. MainActivity shows empty inbox (ConversationRegistry lost)
            if (!awaitingF2pEngine
                    && modeSwitchDialog != null && modeSwitchDialog.isShowing()) {
                Log.w("ChatActivity",
                        "Mode switch finally: dismissing stuck dialog for " + mode);
                dismissModeSwitchDialog(false);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Mode-switch buffering dialog
    // ---------------------------------------------------------------

    /**
     * Shows a non-cancelable progress dialog with "Switching Communication Mode…"
     * and a spinning indicator.  Replaces the old simple ProgressBar so the user
     * gets clear visual feedback during mode transitions.
     */
    private void showModeSwitchDialog() {
        if (isActivityDestroyed) return;
        dismissModeSwitchDialog(false); // avoid duplicates
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Switching Communication Mode…");
            builder.setMessage("Please wait while the transport switches.");
            builder.setCancelable(false);
            // Add an indeterminate ProgressBar inside the dialog
            ProgressBar spinner = new ProgressBar(this);
            spinner.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            spinner.setIndeterminate(true);
            spinner.setIndeterminateTintList(
                    android.content.res.ColorStateList.valueOf(
                            getColor(R.color.primary_red)));
            builder.setView(spinner);
            modeSwitchSpinner = spinner;
            modeSwitchDialog = builder.show();
        } catch (Exception e) {
            Log.w("ChatActivity", "Failed to show mode-switch dialog", e);
            // Fallback: show the old-style buffering spinner
            if (bufferingProgress != null) {
                bufferingProgress.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Dismisses the mode-switch progress dialog with optional success feedback.
     * <p>
     * When {@code success} is {@code true}, the dialog title changes to
     * "✓ Connected", the message shows the transport label, the spinner
     * is replaced with a green checkmark, and the dialog auto-dismisses
     * after 600ms — giving the user clear visual feedback that the
     * transition completed successfully.
     * When {@code false}, the dialog is dismissed immediately.
     * Safe to call multiple times.
     */
    private void dismissModeSwitchDialog(boolean success) {
        // Cancel the timeout runnable so it doesn't fire after a successful switch
        if (modeSwitchTimeoutRunnable != null) {
            modeSwitchTimeoutHandler.removeCallbacks(modeSwitchTimeoutRunnable);
            modeSwitchTimeoutRunnable = null;
        }

        if (success && modeSwitchDialog != null && modeSwitchDialog.isShowing()) {
            // ── Success feedback: show checkmark briefly, then auto-dismiss ──
            try {
                // Update the dialog content to show success
                String modeLabel = currentTransportMode != null
                        ? currentTransportMode.getLabel()
                        : "Transport";
                modeSwitchDialog.setTitle("\u2713 Connected");
                modeSwitchDialog.setMessage(modeLabel + " ready");

                // Replace the indeterminate spinner with a green checkmark
                android.widget.TextView checkmark = new android.widget.TextView(this);
                checkmark.setText("\u2713");
                checkmark.setTextSize(48);
                checkmark.setGravity(android.view.Gravity.CENTER);
                checkmark.setTextColor(0xFF4CAF50);
                checkmark.setPadding(0, 16, 0, 8);

                if (modeSwitchSpinner != null) {
                    android.view.ViewGroup parent = (android.view.ViewGroup)
                            modeSwitchSpinner.getParent();
                    if (parent != null) {
                        int idx = parent.indexOfChild(modeSwitchSpinner);
                        parent.removeView(modeSwitchSpinner);
                        parent.addView(checkmark, idx);
                    }
                    modeSwitchSpinner = null;
                }

                // Cancel any previous timeout, then post a new 600ms auto-dismiss
                // (calls ourselves with success=false to actually dismiss)
                modeSwitchTimeoutHandler.postDelayed(() ->
                        dismissModeSwitchDialog(false), 600);
                return; // Don't dismiss yet — the delayed callback will
            } catch (Exception e) {
                Log.w("ChatActivity", "Error showing success state on mode-switch dialog", e);
                // Fall through to immediate dismiss
            }
        }

        // ── Normal/immediate dismiss (failure, timeout, or after success delay) ──
        if (modeSwitchDialog != null && modeSwitchDialog.isShowing()) {
            try {
                modeSwitchDialog.dismiss();
            } catch (Exception e) {
                Log.w("ChatActivity", "Error dismissing mode-switch dialog", e);
            }
            modeSwitchDialog = null;
        }
        // Hide the fallback ProgressBar in case it was shown
        if (bufferingProgress != null) {
            bufferingProgress.setVisibility(View.GONE);
        }
        // Reset the mode-switching flag so the guard at the top of
        // onTransportModeChanged() and the timeout runnable both know
        // the transition is complete.
        isModeSwitching = false;

        // ── Process deferred mode switch (rapid tap queued via pendingMode) ──
        // If the user tapped a different radio button while the previous
        // switch was still in progress, that mode was stored in pendingMode.
        // Now that isModeSwitching is false, fire the deferred switch.
        if (pendingMode != null && pendingMode != currentTransportMode) {
            TransportMode deferred = pendingMode;
            pendingMode = null;
            Log.d("ChatActivity", "Processing deferred mode switch to " + deferred);
            // Directly fire the mode switch instead of going through
            // the OnCheckedChangeListener (which would hit the mode guard).
            onTransportModeChanged(deferred);
        } else {
            // Clear pending if it matched the current mode (user double-tapped
            // the same button, or the deferred mode became irrelevant).
            pendingMode = null;
        }
    }

    // ---------------------------------------------------------------
    //  Peer bar (compact inline list)
    // ---------------------------------------------------------------

    private void refreshPeerBar() {
        int knownCount = 0;
        int activeCount = 0;
        if (engineReady && bridge != null) {
            try {
                knownCount = bridge.getKnownPeerCount();
                activeCount = bridge.getActiveNodeCount();
            } catch (Exception e) {
                Log.w("ChatActivity", "Failed to get peer counts", e);
            }
        }

        // Build peer list (simulated from counts)
        java.util.List<PeerDevice> devices = new ArrayList<>();
        for (int i = 0; i < Math.max(knownCount, 2); i++) {
            String name = "Device " + (i + 1);
            int signal = (i % 4) + 1;
            boolean connected = i < activeCount;
            devices.add(new PeerDevice("peer-" + i, name, signal, connected));
        }

        if (peerBarAdapter != null) {
            peerBarAdapter.updatePeers(devices);
        }

        // Update badge
        if (peerCountBadge != null) {
            peerCountBadge.setText(String.valueOf(devices.size()));
            peerCountBadge.setVisibility(View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------
    //  Send — immediate local render + async dispatch
    // ---------------------------------------------------------------

    private void sendCurrentMessage() {
        EditText input = findViewById(R.id.inputMessage);
        String messageText = input.getText().toString().trim();
        if (messageText.isEmpty()) return;

        // 1. Validate recipient phone number (E.164 format required)
        String recipientPhone = inputRecipientPhone.getText().toString().trim();
        if (recipientPhone.isEmpty()) {
            inputRecipientPhone.setError("Enter recipient phone number");
            inputRecipientPhone.requestFocus();
            return;
        }
        if (!MessageEncryptor.isValidE164(recipientPhone)) {
            inputRecipientPhone.setError("Invalid format — use +[country][number] (e.g. +8801XXXXXXXX)");
            inputRecipientPhone.requestFocus();
            return;
        }

        // 2. Clear input immediately
        input.setText("");

        // 3. Render locally (with F2P identity)
        String myPhone = UserIdentity.getPhoneNumber(this);
// 4. Determine transport mode for dispatch
        TransportMode mode = radioIdToTransportMode(
                transportRadioGroup.getCheckedRadioButtonId());

        // 5. Build outbound bubble with the right transport code + SENDING status
        //    so it transitions to STATUS_SENT/STATUS_FAILED once the dispatcher
        //    reports back (see onSent/onSendFailed below).
        int transportCode = currentTransportCode();
        MessageModel outbound = new MessageModel(messageText, true, myPhone, recipientPhone,
                transportCode, MessageModel.STATUS_SENDING);
        allMessages.add(outbound);
        addMessage(outbound);

        // ── Register in conversation inbox ──────────────────────
        String resolvedRecipientName = com.antor.sosblue.notification.NotificationHelper
                .lookupDisplayName(recipientPhone);
        ConversationRegistry.update(
                recipientPhone,
                resolvedRecipientName,
                messageText,
                System.currentTimeMillis(),
                true,   // isOutgoing
                false,  // hasMedia
                false,  // incrementUnread (reset on outgoing)
                mode.name()
        );

        RecyclerView chatList = findViewById(R.id.chatRecyclerView);
        final RecyclerView.AdapterDataObserver scrollObserver =
                new RecyclerView.AdapterDataObserver() {
                    @Override
                    public void onItemRangeInserted(int positionStart, int itemCount) {
                        chatList.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
                        chatAdapter.unregisterAdapterDataObserver(this);
                    }
                };
        chatAdapter.registerAdapterDataObserver(scrollObserver);

        // 5. Show inline progress
        showSendProgress(true);

        // 6. Dispatch async using the entered recipient phone number
        if (bridge == null) {
            showSendProgress(false);
            Log.e("ChatActivity", "Cannot send message: bridge is null");
            View __root = findViewById(R.id.root);
            if (__root != null) {
                Snackbar.make(__root,
                        "Cannot send: bridge not initialized",
                        Snackbar.LENGTH_LONG).show();
            }
            return;
        }
        bridge.sendMessageAsync(messageText, recipientPhone, mode,
                new F2PBridge.OnMessageSendListener() {
                    @Override
                    public void onSent() {
                        showSendProgress(false);
                        markMessageStatus(outbound, MessageModel.STATUS_SENT);
                    }

                    @Override
                    public void onSendFailed(String reason) {
                        showSendProgress(false);
markMessageStatus(outbound, MessageModel.STATUS_FAILED);
                        View __root = findViewById(R.id.root);
                        if (__root != null) {
                            Snackbar.make(__root,
                                    "Send failed: " + reason,
                                    Snackbar.LENGTH_LONG).show();
                        }
                    }
                });
    }

    /**
     * Updates the status of an in-flight message in the adapter and persists
     * history so the bubble reflects the new state after rotation or
     * cold-start.
     */
    private void markMessageStatus(@NonNull MessageModel msg, int newStatus) {
        if (msg.getStatus() == newStatus) return;
        msg.setStatus(newStatus);
        // Find the message by id so the right bubble redraws
        java.util.List<MessageModel> current = chatAdapter.getCurrentList();
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).getId() == msg.getId()) {
                chatAdapter.notifyItemChanged(i);
                break;
            }
        }
        persistHistory();
    }

    // ---------------------------------------------------------------
    //  Incoming media chunk reassembly
    // ---------------------------------------------------------------

    /**
     * Processes an incoming media chunk: decrypts, feeds it into the
     * reassembly buffer, and renders the final media message when all
     * chunks have arrived.
     */
    private void handleMediaChunk(String senderPhone, String myPhone,
                                   String transferId, String payloadStr,
                                   byte[] decryptedChunkData) {
        try {
            // Extract chunk metadata from the signal payload
            int chunkIndex = Integer.parseInt(
                    JsonPayloadHelper.extractField(payloadStr, "chunk_index"));
            int totalChunks = Integer.parseInt(
                    JsonPayloadHelper.extractField(payloadStr, "total_chunks"));
            String fileName = JsonPayloadHelper.extractField(payloadStr, "file_name");
            String mimeType = JsonPayloadHelper.extractField(payloadStr, "mime_type");
            int contentType = Integer.parseInt(
                    JsonPayloadHelper.extractField(payloadStr, "content_type"));
            String caption = JsonPayloadHelper.extractField(payloadStr, "caption");

            if (fileName == null) fileName = "unknown";
            final String resolvedMimeType = (mimeType != null) ? mimeType : "application/octet-stream";

            // Compute checksum for integrity verification
            byte[] checksum = com.antor.sosblue.media.MediaChunker
                    .computeChecksum(decryptedChunkData);

            // Build a MediaChunk and feed it to the reassembler
            com.antor.sosblue.media.MediaChunker.MediaChunk chunk =
                    new com.antor.sosblue.media.MediaChunker.MediaChunk(
                            transferId, chunkIndex, totalChunks,
                            fileName, resolvedMimeType, decryptedChunkData,
                            checksum
                    );

            byte[] assembled = com.antor.sosblue.media.MediaChunker.feedChunk(chunk);

            if (assembled != null) {
                // All chunks received — save to local cache and render
                File cacheDir = new File(getCacheDir(), "media_received");
                cacheDir.mkdirs();
                File outFile = new File(cacheDir, transferId + "_" + fileName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                fos.write(assembled);
                fos.close();

                Uri savedUri = Uri.fromFile(outFile);
                long fileSize = assembled.length;

                android.util.Log.i("ChatActivity",
                        "Media reassembled: " + fileName + " (" + fileSize + " bytes)");

                // Render on UI thread
                final Uri mediaUri = savedUri;
                final String cap = (caption != null && !caption.isEmpty()) ? caption : "";
                runOnUiThread(() -> {
                    MessageModel inbound = new MessageModel(
                            cap, false, senderPhone, myPhone,
                            contentType, mediaUri.toString(), resolvedMimeType, fileSize,
                            MessageModel.TRANSPORT_F2P, MessageModel.STATUS_DELIVERED);
                    addMessage(inbound);
                });
            } else {
                // Partial — log progress
                int received = com.antor.sosblue.media.MediaChunker.getReceivedChunkCount(transferId);
                android.util.Log.d("ChatActivity",
                        "Media chunk " + received + "/" + totalChunks + " for " + fileName);
            }
        } catch (Exception e) {
            android.util.Log.e("ChatActivity", "Failed to process media chunk", e);
        }
    }

    /**
     * Processes an incoming SMS media chunk: decrypts the F2P envelope,
     * parses the embedded media metadata via {@link SmsMediaHelper},
     * feeds it into the {@link MediaChunker} reassembly buffer, and
     * renders the final media message when all chunks have arrived.
     * <p>
     * This is the SMS counterpart of {@link #handleMediaChunk} which
     * handles the same flow for UDP-delivered chunks.
     * </p>
     */
    private void handleSmsMediaChunk(String senderPhone, String myPhone,
                                      com.antor.sosblue.bridge.SmsMediaHelper.DecodedChunk decoded) {
        try {
            // Build a MediaChunk and feed it to the reassembler
            com.antor.sosblue.media.MediaChunker.MediaChunk chunk = decoded.toMediaChunk();
            byte[] assembled = com.antor.sosblue.media.MediaChunker.feedChunk(chunk);

            if (assembled != null) {
                // All chunks received — save to local cache and render
                java.io.File cacheDir = new java.io.File(getCacheDir(), "media_received");
                cacheDir.mkdirs();
                java.io.File outFile = new java.io.File(cacheDir,
                        decoded.transferId + "_" + decoded.fileName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                fos.write(assembled);
                fos.close();

                android.net.Uri savedUri = android.net.Uri.fromFile(outFile);
                long fileSize = assembled.length;

                android.util.Log.i("ChatActivity",
                        "SMS media reassembled: " + decoded.fileName
                                + " (" + fileSize + " bytes)");

                // Render on UI thread
                final android.net.Uri mediaUri = savedUri;
                runOnUiThread(() -> {
                    MessageModel inbound = new MessageModel(
                            "", false, senderPhone, myPhone,
                            decoded.contentType, mediaUri.toString(),
                            decoded.mimeType, fileSize,
                            MessageModel.TRANSPORT_SMS, MessageModel.STATUS_DELIVERED);
                    addMessage(inbound);
                });
            } else {
                // Partial — log progress
                int received = com.antor.sosblue.media.MediaChunker
                        .getReceivedChunkCount(decoded.transferId);
                android.util.Log.d("ChatActivity",
                        "SMS media chunk " + received + "/" + decoded.totalChunks
                                + " for " + decoded.fileName);
            }
        } catch (Exception e) {
            android.util.Log.e("ChatActivity", "Failed to process SMS media chunk", e);
        }
    }

    // ---------------------------------------------------------------
    //  Media picker → send
    // ---------------------------------------------------------------

    /**
     * Called when the user selects a photo/video from the system picker.
     * Renders a local preview immediately, then dispatches the encrypted
     * chunks asynchronously via the F2P bridge.
     */
    // ----------------------------------------------------------------
    //  Runtime permission handling (Android 11 / Oppo crash fix)
    // ----------------------------------------------------------------

    /**
     * Requests Wi-Fi/location permissions at runtime if not already granted.
     * <p>
     * On Android 10-12 (API 29-32), {@code ACCESS_FINE_LOCATION} is required
     * for Wi-Fi Direct discovery and Wi-Fi scans. On Android 13+ (API 33+),
     * {@code NEARBY_WIFI_DEVICES} replaces location for nearby device
     * scanning and is often auto-granted by the system.
     * </p>
     * <p>
     * Oppo ColorOS on Android 11 is particularly strict — without this
     * runtime grant, {@code WifiP2pManager.discoverPeers()} throws a
     * {@code SecurityException} and crashes the app.
     * </p>
     */
    private void requestWifiPermissionsIfNeeded() {
        java.util.List<String> needed = new java.util.ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ — NEARBY_WIFI_DEVICES (usually auto-granted)
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12 — need ACCESS_FINE_LOCATION for Wi-Fi scanning
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        // Pre-Android 10: location not required for Wi-Fi scanning

        if (!needed.isEmpty()) {
            wifiPermissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    // ----------------------------------------------------------------
    //  Mesh (Bluetooth + Wi-Fi) permission helpers
    // ----------------------------------------------------------------

    /**
     * Requests Bluetooth + Wi-Fi runtime permissions needed for Mesh mode.
     * <p>
     * On Android 12+ (API 31+), {@code BLUETOOTH_SCAN} and
     * {@code BLUETOOTH_CONNECT} are required for BLE scanning and
     * Wi-Fi Direct peer discovery. On Android 10-11, the older
     * {@code BLUETOOTH} and {@code BLUETOOTH_ADMIN} permissions
     * apply, but Wi-Fi location (ACCESS_FINE_LOCATION) is handled
     * separately via {@link #requestWifiPermissionsIfNeeded()}.
     * </p>
     * <p>
     * This method launches the dedicated mesh permission launcher
     * which is registered in {@link #initializedOnCreate()}.
     * </p>
     */
    private void requestMeshPermissionsIfNeeded() {
        java.util.List<String> needed = new java.util.ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ — BLUETOOTH_SCAN + BLUETOOTH_CONNECT
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            // Pre-Android 12 — BLUETOOTH + BLUETOOTH_ADMIN
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_ADMIN)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
        }

        if (!needed.isEmpty()) {
            meshPermissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    // ----------------------------------------------------------------
    //  SMS permission helpers
    // ----------------------------------------------------------------

    /**
     * @return {@code true} only when SEND_SMS and RECEIVE_SMS are both
     *         already granted. READ_PHONE_STATE is best-effort.
     */
    private boolean hasAllSmsPermissions() {
        boolean send = ContextCompat.checkSelfPermission(this,
                Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean receive = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        return send && receive;
    }

    /**
     * Reverts the transport radio to whichever was active BEFORE the user
     * tapped SMS Relay. Called when the OS denies the SMS permission
     * dialog, or when telephony is unavailable.
     */
    /**
     * Reverts the radio group to the last-saved transport mode.
     * Used when SMS permission is denied or SIM is absent.
     */
    private void revertRadioToLastSaved() {
        TransportMode saved = TransportMode.load(this);
        int targetId;
        switch (saved) {
            case F2P_SERVERLESS:
                targetId = R.id.rb_f2p_serverless;
                break;
            case SMS_FALLBACK:
                targetId = R.id.rb_sms_fallback;
                break;
            default:
                targetId = R.id.rb_f2p_serverless;
                break;
        }
        if (transportRadioGroup.getCheckedRadioButtonId() != targetId) {
            transportRadioGroup.check(targetId);
        }
    }

    /**
     * Requests POST_NOTIFICATIONS on Android 13+ if not already granted.
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /**
     * Triggers the system permission dialog for SMS carrier access. Called
     * by the radio listener when SMS Relay is selected and permissions
     * are not already granted.
     */
    private void requestSmsPermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.SEND_SMS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECEIVE_SMS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_SMS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (!needed.isEmpty()) {
            smsPermissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    /**
     * Public hook for the F2PBridge / SmsTransport to surface a failure
     * reason to the user via Snackbar. Safe to call from any thread
     * (post-runs to the main thread).
     */
    public void showSmsError(String reason) {
        if (isActivityDestroyed) return;
        final String msg = (reason == null || reason.isEmpty())
                ? getString(R.string.sms_send_failed)
                : getString(R.string.sms_send_failed) + ": " + reason;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (isActivityDestroyed) return;
            View __root = findViewById(R.id.root);
            if (__root != null) {
                Snackbar.make(__root, msg, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    // ---------------------------------------------------------------
    //  Search bar toggle
    // ---------------------------------------------------------------

    private void toggleSearchBar() {
        if (searchVisible) {
            hideSearchBar();
        } else {
            showSearchBar();
        }
    }

    private void showSearchBar() {
        searchVisible = true;
        View searchBar = findViewById(R.id.searchBarOverlay);
        if (searchBar != null) {
            searchBar.setVisibility(View.VISIBLE);
            searchBar.setAlpha(0f);
            searchBar.animate().alpha(1f).setDuration(200).start();
        }
        if (searchInput != null) {
            searchInput.requestFocus();
        }
    }

    private void hideSearchBar() {
        searchVisible = false;
        View searchBar = findViewById(R.id.searchBarOverlay);
        if (searchBar != null) {
            searchBar.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> searchBar.setVisibility(View.GONE))
                    .start();
        }
        if (searchInput != null) {
            searchInput.setText("");
        }
        // Restore full list
        if (!allMessages.isEmpty()) {
            chatAdapter.submitList(new ArrayList<>(allMessages));
        }
    }

    // ---------------------------------------------------------------
    //  About dialog
    // ---------------------------------------------------------------

    private void showAboutDialog() {
        String myPhone = UserIdentity.getPhoneNumber(this);
        String testPhone = myPhone != null ? myPhone : "+8801712345678";
        // Dummy number guaranteed to differ from the user's real phone — used
        // for the wrong-key rejection test (Test 3). "+00000000000" is not
        // a valid E.164 number (country code 0 doesn't exist), but it's fine
        // for key derivation; SHA-256 accepts any string.
        String otherPhone = "+00000000000";

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_about_title)
                .setMessage(R.string.dialog_about_message)
                .setPositiveButton(R.string.dialog_ok, null)
                .setNeutralButton("🔬 Crypto Self-Test", (d, w) -> {
                    Log.i("ChatActivity", "--- User triggered crypto self-test ---");
                    boolean passed = MessageEncryptor.runSelfTest(testPhone, otherPhone);
                    String msg = passed
                            ? "✅ All 5 crypto tests PASSED\n\nCheck logcat (tag: MessageEncryptor) for full details."
                            : "❌ Some crypto tests FAILED\n\nCheck logcat (tag: MessageEncryptor) for details.";
                    new AlertDialog.Builder(this)
                            .setTitle("Crypto Self-Test Result")
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show();
                })
                .show();
    }

    // ---------------------------------------------------------------
    //  Media Download — Save received media to device gallery
    // ---------------------------------------------------------------

    /**
     * Shows a confirmation dialog before saving received media to the device gallery.
     * Displays file name, size, and type. On confirm, requests storage permission
     * (pre-Android 10) or directly saves using MediaStore (Android 10+).
     */
    private void showDownloadMediaDialog(MessageModel msg) {
        if (isActivityDestroyed || msg == null) return;

        String fileUri = msg.getMediaUri();
        if (fileUri == null) {
            ToastUtils.showShort(this, "Media file not available");
            return;
        }

        String typeLabel = msg.isVideo() ? "video" : "image";
        String fileName = "unknown";
        if (fileUri != null) {
            try {
                Uri uri = Uri.parse(fileUri);
                String path = uri.getPath();
                if (path != null) {
                    fileName = path.substring(path.lastIndexOf('/') + 1);
                }
            } catch (Exception ignored) {}
        }
        String sizeStr = msg.getFormattedSize();
        String mimeType = msg.getMediaMimeType();
        if (mimeType == null) mimeType = "application/octet-stream";

        String message = String.format(
                getString(R.string.download_media_message),
                typeLabel, fileName, sizeStr.isEmpty() ? "Unknown" : sizeStr, mimeType
        );

        new AlertDialog.Builder(this)
                .setTitle(R.string.download_media_title)
                .setMessage(message)
                .setPositiveButton(R.string.download_media_confirm, (dialog, which) -> {
                    saveMediaToGallery(msg);
                })
                .setNegativeButton(R.string.download_media_cancel, null)
                .show();
    }

    /**
     * Saves a received media file from the app's internal cache to the
     * device's public gallery using MediaStore (Android 10+) or direct
     * file write (Android 9 and below). Runs I/O on a background thread
     * to avoid ANR on large video files.
     */
    private void saveMediaToGallery(MessageModel msg) {
        if (msg == null || msg.getMediaUri() == null) return;

        // ── Storage permission check for pre-Android 10 ────────────
        // Android 10+ (API 29+) uses MediaStore - no storage permission needed.
        // Android 9 and below needs WRITE_EXTERNAL_STORAGE.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestStoragePermissionForDownload(msg);
                return;
            }
        }

        final ChatActivity activity = this;
        final String uriStr = msg.getMediaUri();
        final String mimeType = (msg.getMediaMimeType() != null)
                ? msg.getMediaMimeType()
                : (msg.isVideo() ? "video/mp4" : "image/jpeg");
        final boolean isVideo = msg.isVideo();

        // Run I/O on background thread to keep UI responsive
        new Thread(() -> {
            try {
                // ── Show the download progress bar ─────────────────────
                runOnUiThread(() -> {
                    if (isActivityDestroyed) return;
                    showDownloadProgress(0, "0%");
                });

                Uri cacheUri = Uri.parse(uriStr);
                String path = cacheUri.getPath();
                if (path == null) {
                    runOnUiThread(() -> { if (isActivityDestroyed) return;
                        showDownloadProgress(0, "Failed");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isActivityDestroyed) return;
                            hideDownloadProgress();
                        }, 1200);
                        ToastUtils.showShort(activity, "Media file path not found"); });
                    return;
                }

                // Extract filename from path
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                if (fileName.isEmpty()) fileName = "Offline36_media_" + System.currentTimeMillis();

                File mediaFile = new File(path);
                if (!mediaFile.exists()) {
                    runOnUiThread(() -> { if (isActivityDestroyed) return;
                        showDownloadProgress(0, "Failed");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isActivityDestroyed) return;
                            hideDownloadProgress();
                        }, 1200);
                        ToastUtils.showShort(activity, "Media file no longer available"); });
                    return;
                }

                // Read file bytes with progress reporting
                long fileLen = mediaFile.length();
                if (fileLen > Integer.MAX_VALUE) {
                    runOnUiThread(() -> { if (isActivityDestroyed) return;
                        showDownloadProgress(0, "Failed");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isActivityDestroyed) return;
                            hideDownloadProgress();
                        }, 1200);
                        ToastUtils.showShort(activity, "File too large to save"); });
                    return;
                }
                byte[] fileBytes;
                try (java.io.FileInputStream fis = new java.io.FileInputStream(mediaFile)) {
                    fileBytes = new byte[(int) fileLen];
                    int offset = 0;
                    int bytesRead;
                    final int totalLen = fileBytes.length;
                    int lastReportedPct = -5; // ensures first update fires
                    while (offset < fileBytes.length
                            && (bytesRead = fis.read(fileBytes, offset, fileBytes.length - offset)) != -1) {
                        offset += bytesRead;
                        // Throttle progress to at most 5% increments to avoid UI thread spam
                        final int pct = (int) ((long) offset * 70 / totalLen);
                        if (pct >= lastReportedPct + 5) {
                            lastReportedPct = pct;
                            final int reportedPct = pct;
                            runOnUiThread(() -> {
                                if (isActivityDestroyed) return;
                                showDownloadProgress(reportedPct, reportedPct + "%");
                            });
                        }
                    }
                }

                if (fileBytes.length == 0) {
                    runOnUiThread(() -> { if (isActivityDestroyed) return;
                        hideDownloadProgress();
                        ToastUtils.showShort(activity, "Failed to read media file"); });
                    return;
                }

                // Save — show 90% before write
                runOnUiThread(() -> {
                    if (isActivityDestroyed) return;
                    showDownloadProgress(90, "Writing…");
                });

                // Save based on API level (child methods show their own Toasts)
                boolean saved;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saved = saveViaMediaStore(activity, fileBytes, fileName, mimeType, isVideo);
                } else {
                    saved = saveViaDirectFile(activity, fileBytes, fileName, isVideo);
                }

                if (saved) {
                    // Success — show 100% briefly then hide progress
                    runOnUiThread(() -> {
                        if (isActivityDestroyed) return;
                        showDownloadProgress(100, "100%");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isActivityDestroyed) return;
                            hideDownloadProgress();
                        }, 400);
                    });
                } else {
                    // Failure — child method already showed error Toast
                    runOnUiThread(() -> {
                        if (isActivityDestroyed) return;
                        showDownloadProgress(0, "Failed");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isActivityDestroyed) return;
                            hideDownloadProgress();
                        }, 1200);
                    });
                }
            } catch (Exception e) {
                Log.e("ChatActivity", "Failed to save media to gallery", e);
                runOnUiThread(() -> { if (isActivityDestroyed) return;
                    // Keep progress visible briefly so user sees the problem
                    showDownloadProgress(0, "Failed");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (isActivityDestroyed) return;
                        hideDownloadProgress();
                    }, 1200);
                    ToastUtils.showShort(activity,
                            activity.getString(R.string.download_media_failed) + ": " + e.getMessage()); });
            }
        }).start();
    }

    /**
     * Saves media to the device gallery using MediaStore API (Android 10+).
     * This does NOT require any storage permission. Runs on the calling thread
     * — caller should invoke from a background thread for large files.
     */
    private boolean saveViaMediaStore(android.content.Context context,
                                    byte[] fileBytes, String fileName,
                                    String mimeType, boolean isVideo) {
        try {
            // Determine the target collection and directory
            Uri collection;
            String relativePath;
            if (isVideo) {
                collection = android.provider.MediaStore.Video.Media
                        .EXTERNAL_CONTENT_URI;
                relativePath = Environment.DIRECTORY_MOVIES + "/Offline-36";
            } else {
                collection = android.provider.MediaStore.Images.Media
                        .EXTERNAL_CONTENT_URI;
                relativePath = Environment.DIRECTORY_PICTURES + "/Offline-36";
            }

            // Create metadata
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath);

            // Mark as pending so other apps don't see incomplete file
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            }

            Uri uri = context.getContentResolver().insert(collection, values);
            if (uri == null) {
                runOnUiThread(() ->
                        ToastUtils.showShort(context, "Failed to create media entry in gallery"));
                return false;
            }

            // Write the file bytes
            try (java.io.OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    runOnUiThread(() ->
                            ToastUtils.showShort(context, "Failed to open output stream"));
                    return false;
                }
                os.write(fileBytes);
                os.flush();
            }

            // Mark as complete
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            }

            runOnUiThread(() -> {
                if (isActivityDestroyed) return;
                ToastUtils.showShort(context,
                        context.getString(R.string.download_media_saved));
            });
            Log.i("ChatActivity", "Media saved to gallery: " + fileName);
            return true;

        } catch (Exception e) {
            Log.e("ChatActivity", "Failed to save via MediaStore", e);
            runOnUiThread(() -> {
                if (isActivityDestroyed) return;
                ToastUtils.showShort(context,
                        context.getString(R.string.download_media_failed) + ": " + e.getMessage());
            });
            return false;
        }
    }

    /**
     * Saves media directly to external storage (Android 9 and below).
     * Requires WRITE_EXTERNAL_STORAGE permission. Runs on the calling
     * thread — caller should invoke from a background thread.
     */
    private boolean saveViaDirectFile(android.content.Context context,
                                    byte[] fileBytes, String fileName,
                                    boolean isVideo) {
        try {
            // Determine directory based on media type
            String dirType = isVideo
                    ? Environment.DIRECTORY_MOVIES
                    : Environment.DIRECTORY_PICTURES;
            File mediaDir = Environment.getExternalStoragePublicDirectory(dirType);
            File offline36Dir = new File(mediaDir, "Offline-36");
            offline36Dir.mkdirs();

            File outFile = new File(offline36Dir, fileName);

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                fos.write(fileBytes);
                fos.flush();
            }

            // Notify the media scanner so the file appears immediately
            try {
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(outFile));
                context.sendBroadcast(scanIntent);
            } catch (Exception ignored) {}

            runOnUiThread(() -> {
                if (isActivityDestroyed) return;
                ToastUtils.showShort(context,
                        context.getString(R.string.download_media_saved));
            });
            Log.i("ChatActivity", "Media saved to: " + outFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            Log.e("ChatActivity", "Failed to save directly to storage", e);
            runOnUiThread(() -> {
                if (isActivityDestroyed) return;
                ToastUtils.showShort(context,
                        context.getString(R.string.download_media_failed) + ": " + e.getMessage());
            });
            return false;
        }
    }

    // ---------------------------------------------------------------
    //  Download Progress UI
    // ---------------------------------------------------------------

    /**
     * Shows or updates the download progress bar at the bottom of the chat area.
     * Called from the background download thread via runOnUiThread.
     *
     * @param percent  0-100 progress percentage
     * @param status   short status label (e.g. "Saving...", "42%")
     */
    private void showDownloadProgress(int percent, String status) {
        if (downloadProgressContainer == null) return;
        if (downloadProgressContainer.getVisibility() != View.VISIBLE) {
            downloadProgressContainer.setVisibility(View.VISIBLE);
            downloadProgressContainer.setAlpha(0f);
            downloadProgressContainer.animate().alpha(1f).setDuration(200).start();
        }
        if (downloadProgressBar != null) {
            downloadProgressBar.setProgress(percent);
        }
        if (downloadProgressText != null) {
            downloadProgressText.setText(status);
        }
    }

    /**
     * Hides the download progress bar with a fade-out animation.
     */
    private void hideDownloadProgress() {
        if (downloadProgressContainer == null) return;
        if (downloadProgressContainer.getVisibility() == View.VISIBLE) {
            downloadProgressContainer.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        if (downloadProgressContainer != null) {
                            downloadProgressContainer.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    /**
     * Requests WRITE_EXTERNAL_STORAGE permission on Android 9 and below.
     * Stores the pending message so the callback can retry the save.
     */
    private void requestStoragePermissionForDownload(MessageModel msg) {
        pendingDownloadMessage = msg;
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void onMediaSelected(Uri uri) {
        String recipientPhone = inputRecipientPhone.getText().toString().trim();
        if (recipientPhone.isEmpty() || !MessageEncryptor.isValidE164(recipientPhone)) {
            Snackbar.make(findViewById(R.id.root),
                    "Enter a valid recipient phone number first",
                    Snackbar.LENGTH_SHORT).show();
            return;
        }

        // Resolve metadata
        String fileName = com.antor.sosblue.media.MediaChunker.getFileName(this, uri);
        String mimeType = getContentResolver().getType(uri);
        if (mimeType == null) mimeType = "application/octet-stream";
        long fileSize = com.antor.sosblue.media.MediaChunker.getFileSize(this, uri);

        boolean isVideo = mimeType.startsWith("video/");
        int contentType = isVideo
                ? MessageModel.TYPE_VIDEO : MessageModel.TYPE_IMAGE;

        // Render a local outgoing media bubble immediately
        String myPhone = UserIdentity.getPhoneNumber(this);
        MessageModel mediaMsg = new MessageModel(
                "", true, myPhone, recipientPhone,
                contentType, uri.toString(), mimeType, fileSize,
                currentTransportCode(), MessageModel.STATUS_SENDING);

        RecyclerView chatList = findViewById(R.id.chatRecyclerView);
        final RecyclerView.AdapterDataObserver scrollObserver =
                new RecyclerView.AdapterDataObserver() {
                    @Override
                    public void onItemRangeInserted(int positionStart, int itemCount) {
                        chatList.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
                        chatAdapter.unregisterAdapterDataObserver(this);
                    }
                };
        chatAdapter.registerAdapterDataObserver(scrollObserver);
        addMessage(mediaMsg);

        // Show progress
        showSendProgress(true);

        // Dispatch encrypted chunks async
        TransportMode mode = radioIdToTransportMode(
                transportRadioGroup.getCheckedRadioButtonId());

        if (bridge == null) {
            showSendProgress(false);
            Log.e("ChatActivity", "Cannot send media: bridge is null");
            View __root = findViewById(R.id.root);
            if (__root != null) {
                Snackbar.make(__root,
                        "Cannot send: bridge not initialized",
                        Snackbar.LENGTH_LONG).show();
            }
            return;
        }
        bridge.sendMediaAsync(this, uri, recipientPhone, mode, "",
                new com.antor.sosblue.media.MediaTransferListener() {
                    @Override
                    public void onProgress(String transferId, int chunksSent, int totalChunks) {
                        // Could update a per-message progress bar here
                        Log.d("ChatActivity", "Media chunk " + chunksSent + "/" + totalChunks);
                    }

                    @Override
                    public void onComplete(String transferId, byte[] assembledData) {
                        showSendProgress(false);
                        markMessageStatus(mediaMsg, MessageModel.STATUS_SENT);
                        ToastUtils.showShort(ChatActivity.this, "Media sent successfully");
                    }

                    @Override
                    public void onFailed(String transferId, String reason) {
                        showSendProgress(false);
markMessageStatus(mediaMsg, MessageModel.STATUS_FAILED);
                        View __root = findViewById(R.id.root);
                        if (__root != null) {
                            Snackbar.make(__root,
                                    "Media send failed: " + reason,
                                    Snackbar.LENGTH_LONG).show();
                        }
                    }
                });
    }

    /**
     * Map the currently-selected {@link TransportMode} to the
     * {@link MessageModel#TRANSPORT_MESH}/{@link MessageModel#TRANSPORT_F2P}/
     * {@link MessageModel#TRANSPORT_SMS} integer constants so newly-created
     * outgoing messages can be tagged with the channel that will carry them.
     */
    private int currentTransportCode() {
        TransportMode mode = TransportMode.load(this);
        switch (mode) {
            case SOSBLUE_MESH:   return MessageModel.TRANSPORT_MESH;
            case F2P_SERVERLESS: return MessageModel.TRANSPORT_F2P;
            case SMS_FALLBACK:   return MessageModel.TRANSPORT_SMS;
            default:             return MessageModel.TRANSPORT_UNKNOWN;
        }
    }
}
