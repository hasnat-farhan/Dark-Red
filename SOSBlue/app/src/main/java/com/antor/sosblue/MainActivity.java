package com.antor.sosblue;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.antor.f2p.engine.api.EngineConfig;
import com.antor.sosblue.bridge.F2PBridge;
import com.antor.sosblue.bridge.TransportMode;

import com.antor.sosblue.util.ToastUtils;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {

    private F2PBridge bridge;
    private RadioGroup transportRadioGroup;
    private TextView textStatus;
    private ChatAdapter chatAdapter;
    private View loadingContainer;
    private View sendButton;

    // Peer discovery
    private View peerPanel;
    private RecyclerView peerRecyclerView;
    private TextView peerEmptyHint;
    private TextView peerCountLabel;
    private PeerDiscoveryAdapter peerAdapter;
    private boolean peerPanelVisible;
    private boolean engineReady;

    /** Cache of real discovered peers (nodeId → PeerDevice). */
    private final ConcurrentHashMap<String, PeerDevice> discoveredPeers = new ConcurrentHashMap<>();
    /** Maps peer nodeId → phone number for E2E encryption key derivation. */
    private final ConcurrentHashMap<String, String> peerPhoneNumbers = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });

        // Initialise F2P Bridge
        bridge = new F2PBridge(this);

        // ── Register for real peer discovery events ────────────────
        bridge.addPeerDiscoveryListener(new F2PBridge.PeerDiscoveryListener() {
            @Override
            public void onPeerDiscovered(String nodeId, String username, String phone,
                                          String ipAddress, int port) {
                String displayName = (username != null && !username.isEmpty()) ? username
                        : (phone != null ? phone : nodeId);
                String id = nodeId != null ? nodeId : (phone != null ? phone : "unknown");
                PeerDevice peer = new PeerDevice(id, displayName, 3, true,
                        ipAddress, port);
                discoveredPeers.put(id, peer);
                // Store phone number separately for E2E encryption key derivation
                if (phone != null) {
                    peerPhoneNumbers.put(id, phone);
                }
                runOnUiThread(() -> refreshPeerList());
            }

            @Override
            public void onPeerLost(String nodeId) {
                discoveredPeers.remove(nodeId);
                peerPhoneNumbers.remove(nodeId);
                runOnUiThread(() -> refreshPeerList());
            }
        });

        // References
        textStatus = findViewById(R.id.textStatus);
        transportRadioGroup = findViewById(R.id.transportRadioGroup);
        loadingContainer = findViewById(R.id.loadingContainer);
        sendButton = findViewById(R.id.sendButton);

        // ---------------------------------------------------------------
        //  Chat RecyclerView (in-memory adapter)
        // ---------------------------------------------------------------

        RecyclerView chatList = findViewById(R.id.chatRecyclerView);
        chatAdapter = new ChatAdapter();
        chatList.setLayoutManager(new LinearLayoutManager(this));
        chatList.setAdapter(chatAdapter);

        // Restore persisted preference
        TransportMode savedMode = TransportMode.load(this);
        if (savedMode == TransportMode.F2P_SERVERLESS) {
            transportRadioGroup.check(R.id.rb_f2p_serverless);
        } else {
            transportRadioGroup.check(R.id.rb_sosblue_mesh);
        }

        // Listen for RadioGroup changes — persist mode
        transportRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            TransportMode mode = radioIdToTransportMode(checkedId);
            mode.save(this);
            onTransportModeChanged(mode);
        });

        // Initial status display (engine not ready yet — skip peer panel)
        onTransportModeChanged(
                radioIdToTransportMode(transportRadioGroup.getCheckedRadioButtonId()));

        // ---------------------------------------------------------------
        //  Top action bar — preserve touch events
        // ---------------------------------------------------------------

        findViewById(R.id.searchIcon).setOnClickListener(v ->
                ToastUtils.showShort(this, "Search"));
        findViewById(R.id.discoverIcon).setOnClickListener(v ->
                ToastUtils.showShort(this, "Discover"));
        findViewById(R.id.threeDotIcon).setOnClickListener(v ->
                ToastUtils.showShort(this, "Menu"));
        findViewById(R.id.switchInputImage).setOnClickListener(v ->
                ToastUtils.showShort(this, "Attach file"));

        // ---------------------------------------------------------------
        //  Peer discovery panel — shows real discovered peers from heartbeats
        // ---------------------------------------------------------------

        peerPanel = findViewById(R.id.peerDiscoveryPanel);
        peerRecyclerView = findViewById(R.id.peerRecyclerView);
        peerEmptyHint = findViewById(R.id.peerEmptyHint);
        peerCountLabel = findViewById(R.id.peerCountLabel);
        Button peerRefreshButton = findViewById(R.id.peerRefreshButton);

        peerAdapter = new PeerDiscoveryAdapter(new ArrayList<>(), peer -> {
            // Launch ChatActivity with the selected peer's phone number
            // (for E2E encryption) and display name (for the chat title).
            Intent intent = new Intent(MainActivity.this, ChatActivity.class);
            String peerId = peer.getId();
            String peerPhone = peerPhoneNumbers.get(peerId);
            if (peerPhone != null) {
                intent.putExtra(ChatActivity.EXTRA_RECIPIENT_PHONE, peerPhone);
            } else {
                // Fallback: use the peer name (may not work for E2E)
                intent.putExtra(ChatActivity.EXTRA_RECIPIENT_PHONE, peer.getName());
            }
            intent.putExtra(ChatActivity.EXTRA_RECIPIENT_NAME, peer.getName());
            startActivity(intent);
            peerPanelVisible = false;
            peerPanel.setVisibility(View.GONE);
        });
        peerRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        peerRecyclerView.setAdapter(peerAdapter);

        // Tap the title bar to toggle the peer panel
        findViewById(R.id.appTitle).setOnClickListener(v -> {
            if (!peerPanelVisible) {
                refreshPeerList();
                showPeerPanel(true);
            } else {
                showPeerPanel(false);
            }
        });

        // Refresh button re-scans peers
        peerRefreshButton.setOnClickListener(v -> refreshPeerList());

        // ---------------------------------------------------------------
        //  Send button
        // ---------------------------------------------------------------

        sendButton.setOnClickListener(v -> sendCurrentMessage());

        // Handle IME action on the message input
        android.widget.EditText inputMessage = findViewById(R.id.inputMessage);
        inputMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage();
                return true;
            }
            return false;
        });

        // ---------------------------------------------------------------
        //  Start engine on a background thread — NEVER block the UI thread
        // ---------------------------------------------------------------

        showLoading(true);

        EngineConfig config = EngineConfig.builder()
                .nodeId("sosblue-" + System.currentTimeMillis())
                .build();

        bridge.startEngineAsync(config, new F2PBridge.OnEngineStartListener() {
            @Override
            public void onEngineStarted() {
                engineReady = true;
                showLoading(false);
                onTransportModeChanged(
                        radioIdToTransportMode(transportRadioGroup.getCheckedRadioButtonId()));
            }

            @Override
            public void onEngineError(int statusCode, String message) {
                showLoading(false);
            }
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

    private void showLoading(boolean show) {
        if (textStatus != null) {
            textStatus.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void showSendProgress(boolean show) {
        if (loadingContainer != null) {
            loadingContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (sendButton != null) {
            sendButton.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private void scrollChatToBottom() {
        RecyclerView chatList = findViewById(R.id.chatRecyclerView);
        if (chatList != null && chatAdapter != null) {
            int count = chatAdapter.getItemCount();
            if (count > 0) {
                chatList.smoothScrollToPosition(count - 1);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Peer discovery panel — real discovered peers from UDP heartbeats
    // ---------------------------------------------------------------

    private void showPeerPanel(boolean show) {
        peerPanelVisible = show;
        if (peerPanel != null) {
            peerPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshPeerList() {
        List<PeerDevice> devices = new ArrayList<>(discoveredPeers.values());

        peerAdapter.updatePeers(devices);

        // Update count label + empty hint
        int activeCount = engineReady ? bridge.getActiveNodeCount() : 0;
        peerCountLabel.setText(devices.size() + " peer(s) discovered · "
                + activeCount + " online");
        peerEmptyHint.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------
    //  Transport mode switching
    // ---------------------------------------------------------------

    private void onTransportModeChanged(TransportMode mode) {
        if (mode == TransportMode.SOSBLUE_MESH) {
            // Show peer discovery panel when Mesh is selected
            if (engineReady) {
                refreshPeerList();
                showPeerPanel(true);
            }
        } else {
            showPeerPanel(false);

            if (!bridge.isRouting()) {
                View root = findViewById(R.id.main);
                Snackbar sb = Snackbar.make(root, "F2P engine not connected",
                        Snackbar.LENGTH_LONG);
                sb.setAction("Switch to Mesh", v -> {
                    transportRadioGroup.check(R.id.rb_sosblue_mesh);
                    ToastUtils.showShort(this, "Switched to SOSBlue Mesh");
                });
                sb.show();
            }
        }
    }

    // ---------------------------------------------------------------
    //  Send message
    // ---------------------------------------------------------------

    private void sendCurrentMessage() {
        String messageText = String.valueOf(
                ((android.widget.EditText) findViewById(R.id.inputMessage)).getText()
        ).trim();
        if (messageText.isEmpty()) return;

        ((android.widget.EditText) findViewById(R.id.inputMessage)).setText("");

        MessageModel outbound = new MessageModel(messageText, true /* sent */);
        java.util.List<MessageModel> updated = new java.util.ArrayList<>(
                chatAdapter.getCurrentList());
        updated.add(outbound);

        final RecyclerView chatList = findViewById(R.id.chatRecyclerView);
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

        showSendProgress(true);

        String recipientId = bridge.getLocalNodeId();
        TransportMode mode = radioIdToTransportMode(
                transportRadioGroup.getCheckedRadioButtonId());

        bridge.sendMessageAsync(messageText, recipientId, mode,
                new F2PBridge.OnMessageSendListener() {
                    @Override
                    public void onSent() {
                        showSendProgress(false);
                    }

                    @Override
                    public void onSendFailed(String reason) {
                        showSendProgress(false);
                    }
                });
    }
}
