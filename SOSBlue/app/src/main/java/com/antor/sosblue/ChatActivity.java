package com.antor.sosblue;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import com.antor.sosblue.identity.F2PMessage;
import com.antor.sosblue.identity.MessageEncryptor;
import com.antor.sosblue.identity.UserIdentity;
import com.antor.sosblue.identity.JsonPayloadHelper;

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

    /** Permission launcher for SMS (SEND_SMS + RECEIVE_SMS + READ_PHONE_STATE). */
    private ActivityResultLauncher<String[]> smsPermissionLauncher;

    /**
     * Saved transport mode BEFORE the user tapped SMS Relay — used to
     * revert the radio when permission is denied or SIM is absent.
     */
    private int lastSavedRadioId = R.id.rb_sosblue_mesh;

    // Chat
    private ChatAdapter chatAdapter;

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

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
                        Snackbar.make(findViewById(R.id.root),
                                "Wi-Fi permissions needed for peer discovery & mesh",
                                Snackbar.LENGTH_LONG).show();
                    } else {
                        Log.i("ChatActivity", "All Wi-Fi/location permissions granted");
                    }
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
                    if (sendGranted && receiveGranted) {
                        Log.i("ChatActivity",
                                "SMS permissions granted (phone=" + phoneGranted + ")");
                        // Re-fire onTransportModeChanged now that permissions
                        // are confirmed — the listener may have already called
                        // it once, but if the user was slow to grant we'd have
                        // bounced off the gate.
                        onTransportModeChanged(TransportMode.SMS_FALLBACK);
                    } else {
                        Log.w("ChatActivity",
                                "SMS permissions denied (send=" + sendGranted
                                        + ", receive=" + receiveGranted
                                        + ", phone=" + phoneGranted + ")");
                        Snackbar.make(findViewById(R.id.root),
                                getString(R.string.transport_sms_permission_required),
                                Snackbar.LENGTH_LONG).show();
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
                    runOnUiThread(() -> {
                        MessageModel inbound = new MessageModel(text, false, sender, myPhone,
                                MessageModel.TRANSPORT_F2P, MessageModel.STATUS_DELIVERED);
                        addMessage(inbound);
                    });
                } catch (Exception e) {
                    android.util.Log.e("ChatActivity", "Failed to process incoming F2P message", e);
                    runOnUiThread(() -> {
                        String senderInfo = "unknown sender";
                        try {
                            String payloadStr2 = new String(packet.getRawDataBuffer(),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            String sender2 = JsonPayloadHelper.extractField(payloadStr2, "sender_phone");
                            if (sender2 != null) senderInfo = sender2;
                        } catch (Exception ignored) {}
                        Snackbar.make(findViewById(R.id.root),
                                "⚠ Could not decrypt message from " + senderInfo,
                                Snackbar.LENGTH_LONG).show();
                    });
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
                    if (!myPhone.equals(recipientPhone)) return; // not for us
                    if (senderPhone == null || encryptedPayload == null) return;

                    byte[] decryptedBytes = MessageEncryptor.decrypt(myPhone, encryptedPayload);
                    String text = new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
                    String sender = UserIdentity.normalizePhoneNumber(senderPhone);
                    final String finalSender = sender != null ? sender : senderPhone;
                    final String finalText = text;
                    runOnUiThread(() -> {
                        MessageModel inbound = new MessageModel(finalText, false, finalSender, myPhone,
                                MessageModel.TRANSPORT_SMS, MessageModel.STATUS_DELIVERED);
                        addMessage(inbound);
                    });
                } catch (Exception e) {
                    android.util.Log.e("ChatActivity", "Failed to decrypt inbound SMS F2P envelope", e);
                }
            }
        };
        sms.addEnvelopeListener(smsEnvelopeListener);

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
            }
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
        if (savedMode == TransportMode.F2P_SERVERLESS) {
            transportRadioGroup.check(R.id.rb_f2p_serverless);
        } else {
            transportRadioGroup.check(R.id.rb_sosblue_mesh);
        }

        transportRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            TransportMode mode = radioIdToTransportMode(checkedId);
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

        findViewById(R.id.searchIcon).setOnClickListener(v ->
                ToastUtils.showShort(this, "Search"));
        findViewById(R.id.discoverIcon).setOnClickListener(v ->
                ToastUtils.showShort(this, "Discover"));
        findViewById(R.id.threeDotIcon).setOnClickListener(v ->
                ToastUtils.showShort(this, "Menu"));

        // "Nearby Devices" title → toggle peer bar
        findViewById(R.id.titleContainer).setOnClickListener(v -> {
            if (peerBarCard.getVisibility() == View.VISIBLE) {
                peerBarCard.setVisibility(View.GONE);
            } else {
                refreshPeerBar();
                peerBarCard.setVisibility(View.VISIBLE);
            }
        });

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

    @Override
    protected void onPause() {
        // Flush the current thread to disk before the activity goes away.
        persistHistory();
        loadHistoryFor(currentRecipientPhone); // no-op if same; re-fetches if swap
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.stopEngine();
        }
        if (pendingSmsTransport != null) {
            if (smsEnvelopeListener != null) {
                pendingSmsTransport.removeEnvelopeListener(smsEnvelopeListener);
            }
            pendingSmsTransport.unregister();
            pendingSmsTransport = null;
        }
        smsEnvelopeListener = null;
        if (historyStore != null) {
            historyStore.shutdown();
            historyStore = null;
        }
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
        if (mode == TransportMode.SOSBLUE_MESH) {
            // Show peer bar with nearby devices
            peerBarCard.setVisibility(View.VISIBLE);
            refreshPeerBar();
        } else if (mode == TransportMode.SMS_FALLBACK) {
            // ── SMS carrier path ─────────────────────────────────────
            // No mesh, no Wi-Fi, no F2P — but messages still need to
            // go out. Lazy-init the SmsTransport so the receiver is
            // wired for inbound envelopes, and make sure permissions
            // are checked before the first send.
            peerBarCard.setVisibility(View.GONE);
            bufferingProgress.setVisibility(View.GONE);
            com.antor.sosblue.bridge.SmsTransport sms = bridge.getSmsTransport();
            if (sms == null) {
                sms = new com.antor.sosblue.bridge.SmsTransport(this);
                bridge.setSmsTransport(sms);
            }
            sms.register();
            pendingSmsTransport = sms;
            ToastUtils.showShort(this, "SMS Relay ready");
        } else {
            // F2P Serverless — hide peer bar, show buffering spinner
            peerBarCard.setVisibility(View.GONE);
            f2pRequested = true;

            if (!engineReady) {
                bufferingProgress.setVisibility(View.VISIBLE);

                // ── F2P: Use phone number as node ID for routing/encryption ──
                String myPhone = UserIdentity.getPhoneNumber(ChatActivity.this);
                EngineConfig config = EngineConfig.builder()
                        .nodeId(myPhone != null ? myPhone : "sosblue-" + System.currentTimeMillis())
                        .build();

                bridge.startEngineAsync(config, new F2PBridge.OnEngineStartListener() {
                    @Override
                    public void onEngineStarted() {
                        engineReady = true;
                        bufferingProgress.setVisibility(View.GONE);
                        refreshPeerBar();
                        ToastUtils.showShort(ChatActivity.this, "F2P Serverless ready");
                    }

                    @Override
                    public void onEngineError(int statusCode, String message) {
                        bufferingProgress.setVisibility(View.GONE);
                        Snackbar.make(findViewById(R.id.root),
                                "F2P engine error: " + message,
                                Snackbar.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    // ---------------------------------------------------------------
    //  Peer bar (compact inline list)
    // ---------------------------------------------------------------

    private void refreshPeerBar() {
        int knownCount = 0;
        int activeCount = 0;
        if (engineReady) {
            knownCount = bridge.getKnownPeerCount();
            activeCount = bridge.getActiveNodeCount();
        }

        // Build peer list (simulated from counts)
        java.util.List<PeerDevice> devices = new ArrayList<>();
        for (int i = 0; i < Math.max(knownCount, 2); i++) {
            String name = "Device " + (i + 1);
            int signal = (i % 4) + 1;
            boolean connected = i < activeCount;
            devices.add(new PeerDevice("peer-" + i, name, signal, connected));
        }
        peerBarAdapter.updatePeers(devices);

        // Update badge
        String badgeText = String.valueOf(devices.size());
        peerCountBadge.setText(badgeText);
        peerCountBadge.setVisibility(View.VISIBLE);
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
        int transportCode = currentTransportCode();
        MessageModel outbound = new MessageModel(messageText, true, myPhone, recipientPhone,
                transportCode, MessageModel.STATUS_SENDING);

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
        addMessage(outbound);

        // 4. Show inline progress
        showSendProgress(true);

        // 5. Dispatch async using the entered recipient phone number
        TransportMode mode = radioIdToTransportMode(
                transportRadioGroup.getCheckedRadioButtonId());

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
                        Snackbar.make(findViewById(R.id.root),
                                "Send failed: " + reason,
                                Snackbar.LENGTH_LONG).show();
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

            // Build a MediaChunk and feed it to the reassembler
            com.antor.sosblue.media.MediaChunker.MediaChunk chunk =
                    new com.antor.sosblue.media.MediaChunker.MediaChunk(
                            transferId, chunkIndex, totalChunks,
                            fileName, resolvedMimeType, decryptedChunkData,
                            new byte[0] // checksum not needed for received chunks
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

    // ---------------------------------------------------------------
    //  Media picker → send
    // ---------------------------------------------------------------

    /**
     * Called when the user selects a photo/video from the system picker.
     * Renders a local preview immediately, then dispatches the encrypted
     * chunks asynchronously via the F2P bridge.
     */
    // ---------------------------------------------------------------
    //  Runtime permission handling (Android 11 / Oppo crash fix)
    // ---------------------------------------------------------------

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
    private void revertRadioToLastSaved() {
        TransportMode saved = TransportMode.load(this);
        int targetId = (saved == TransportMode.F2P_SERVERLESS)
                ? R.id.rb_f2p_serverless
                : R.id.rb_sosblue_mesh;
        if (transportRadioGroup.getCheckedRadioButtonId() != targetId) {
            transportRadioGroup.check(targetId);
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
        final String msg = (reason == null || reason.isEmpty())
                ? getString(R.string.sms_send_failed)
                : getString(R.string.sms_send_failed) + ": " + reason;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_LONG).show());
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
                        Snackbar.make(findViewById(R.id.root),
                                "Media send failed: " + reason,
                                Snackbar.LENGTH_LONG).show();
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
