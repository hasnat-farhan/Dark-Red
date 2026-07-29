package com.antor.sosblue;

import android.content.Intent;
import android.net.Uri;
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
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
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

    // Core
    private F2PBridge bridge;
    private boolean engineReady;
    private boolean f2pRequested;

    // Chat
    private ChatAdapter chatAdapter;

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

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat);

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
                    byte[] envelopeBytes = Base64.decode(envelopeB64, Base64.NO_WRAP);
                    F2PMessage f2pMsg = F2PMessage.deserialize(envelopeBytes);
                    byte[] decryptedBytes = MessageEncryptor.decrypt(
                            senderPhone, f2pMsg.getEncryptedPayload());

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
                        MessageModel inbound = new MessageModel(text, false, sender, myPhone);
                        java.util.List<MessageModel> updated = new ArrayList<>(
                                chatAdapter.getCurrentList());
                        updated.add(inbound);
                        chatAdapter.submitList(updated);
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
            }
        });

        // Pre-fill recipient with the user's own phone (for self-testing)
        // Users can change this to any valid E.164 phone number
        String myPhone = UserIdentity.getPhoneNumber(this);
        if (myPhone != null && inputRecipientPhone.getText().toString().isEmpty()) {
            inputRecipientPhone.setText(myPhone);
        }

        // ---------------------------------------------------------------
        //  Chat RecyclerView
        // ---------------------------------------------------------------

        RecyclerView chatList = findViewById(R.id.chatRecyclerView);
        chatAdapter = new ChatAdapter();
        chatList.setLayoutManager(new LinearLayoutManager(this));
        chatList.setAdapter(chatAdapter);

        // ---------------------------------------------------------------
        //  Peer bar adapter (compact inline list in the CardView)
        //  NOTE: Must be initialised BEFORE onTransportModeChanged()
        //  because that method calls refreshPeerBar() which uses this
        //  adapter.  Previously this was below the transport-mode call
        //  which caused a NullPointerException on cold start.
        // ---------------------------------------------------------------

        peerBarAdapter = new PeerDiscoveryAdapter(new ArrayList<>(), peer -> {
            Toast.makeText(this, "Chat with " + peer.getName(), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "Search", Toast.LENGTH_SHORT).show());
        findViewById(R.id.discoverIcon).setOnClickListener(v ->
                Toast.makeText(this, "Discover", Toast.LENGTH_SHORT).show());
        findViewById(R.id.threeDotIcon).setOnClickListener(v ->
                Toast.makeText(this, "Menu", Toast.LENGTH_SHORT).show());

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
    protected void onDestroy() {
        if (bridge != null) {
            bridge.stopEngine();
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
        return TransportMode.SOSBLUE_MESH;
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
                        Toast.makeText(ChatActivity.this,
                                "F2P Serverless ready", Toast.LENGTH_SHORT).show();
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
        MessageModel outbound = new MessageModel(messageText, true, myPhone, recipientPhone);
        java.util.List<MessageModel> updated = new ArrayList<>(chatAdapter.getCurrentList());
        updated.add(outbound);

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
        chatAdapter.submitList(updated);

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
                    }

                    @Override
                    public void onSendFailed(String reason) {
                        showSendProgress(false);
                        Snackbar.make(findViewById(R.id.root),
                                "Send failed: " + reason,
                                Snackbar.LENGTH_LONG).show();
                    }
                });
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
                            contentType, mediaUri.toString(), resolvedMimeType, fileSize);
                    java.util.List<MessageModel> updated = new ArrayList<>(
                            chatAdapter.getCurrentList());
                    updated.add(inbound);
                    chatAdapter.submitList(updated);
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
                contentType, uri.toString(), mimeType, fileSize);

        java.util.List<MessageModel> updated = new ArrayList<>(chatAdapter.getCurrentList());
        updated.add(mediaMsg);

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
        chatAdapter.submitList(updated);

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
                        Toast.makeText(ChatActivity.this,
                                "Media sent successfully", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailed(String transferId, String reason) {
                        showSendProgress(false);
                        Snackbar.make(findViewById(R.id.root),
                                "Media send failed: " + reason,
                                Snackbar.LENGTH_LONG).show();
                    }
                });
    }
}
