package com.antor.sosblue;

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

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

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

        // Initialise F2P Bridge
        bridge = new F2PBridge(this);

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
                Toast.makeText(this, "Search", Toast.LENGTH_SHORT).show());
        findViewById(R.id.discoverIcon).setOnClickListener(v ->
                Toast.makeText(this, "Discover", Toast.LENGTH_SHORT).show());
        findViewById(R.id.threeDotIcon).setOnClickListener(v ->
                Toast.makeText(this, "Menu", Toast.LENGTH_SHORT).show());
        findViewById(R.id.switchInputImage).setOnClickListener(v ->
                Toast.makeText(this, "Attach file", Toast.LENGTH_SHORT).show());

        // ---------------------------------------------------------------
        //  Peer discovery panel
        // ---------------------------------------------------------------

        peerPanel = findViewById(R.id.peerDiscoveryPanel);
        peerRecyclerView = findViewById(R.id.peerRecyclerView);
        peerEmptyHint = findViewById(R.id.peerEmptyHint);
        peerCountLabel = findViewById(R.id.peerCountLabel);
        Button peerRefreshButton = findViewById(R.id.peerRefreshButton);

        peerAdapter = new PeerDiscoveryAdapter(new ArrayList<>(), peer -> {
            Toast.makeText(this, "Chat with " + peer.getName(), Toast.LENGTH_SHORT).show();
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

    /** Maps a RadioButton ID to the corresponding TransportMode. */
    private static TransportMode radioIdToTransportMode(int radioId) {
        if (radioId == R.id.rb_f2p_serverless) {
            return TransportMode.F2P_SERVERLESS;
        }
        return TransportMode.SOSBLUE_MESH;  // default
    }

    /** Shows or hides the full-screen loading overlay. */
    private void showLoading(boolean show) {
        if (textStatus != null) {
            textStatus.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /** Shows or hides the inline send-progress spinner. */
    private void showSendProgress(boolean show) {
        if (loadingContainer != null) {
            loadingContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (sendButton != null) {
            sendButton.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /** Scrolls the chat RecyclerView to the very last item. */
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
    //  Peer discovery panel
    // ---------------------------------------------------------------

    /** Toggles the peer discovery panel visibility. */
    private void showPeerPanel(boolean show) {
        peerPanelVisible = show;
        if (peerPanel != null) {
            peerPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /** Queries F2PBridge for known peers and updates the list. */
    private void refreshPeerList() {
        // Gather simulated peers from the bridge's known-peers count
        int knownCount = 0;
        int activeCount = 0;

        // engineReady check avoids NPE on routingTable before initialize()
        if (engineReady) {
            knownCount = bridge.getKnownPeerCount();
            activeCount = bridge.getActiveNodeCount();
        }

        List<PeerDevice> devices = new ArrayList<>();
        for (int i = 0; i < Math.max(knownCount, 2); i++) {
            String id = "peer-" + (i + 1) + "-a1b2c3d" + (i + 1);
            String name = "Device " + (i + 1);
            int signal = (i % 4) + 1;
            boolean connected = i < activeCount;
            devices.add(new PeerDevice(id, name, signal, connected));
        }

        peerAdapter.updatePeers(devices);

        // Update count label + empty hint
        peerCountLabel.setText(activeCount + " peer(s) online · "
                + knownCount + " discovered");
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
            // else: engine callback will trigger refresh once ready
        } else {
            // F2P engine stays IDLE until explicitly selected
            showPeerPanel(false);

            if (!bridge.isRouting()) {
                View root = findViewById(R.id.main);
                Snackbar sb = Snackbar.make(root, "F2P engine not connected",
                        Snackbar.LENGTH_LONG);
                sb.setAction("Switch to Mesh", v -> {
                    transportRadioGroup.check(R.id.rb_sosblue_mesh);
                    Toast.makeText(this, "Switched to SOSBlue Mesh", Toast.LENGTH_SHORT).show();
                });
                sb.show();
            }
        }
    }

    // ---------------------------------------------------------------
    //  Send message
    //  Immediately renders locally, then dispatches async
    // ---------------------------------------------------------------

    private void sendCurrentMessage() {
        String messageText = String.valueOf(
                ((android.widget.EditText) findViewById(R.id.inputMessage)).getText()
        ).trim();
        if (messageText.isEmpty()) return;

        // 1. Remove input text immediately
        ((android.widget.EditText) findViewById(R.id.inputMessage)).setText("");

        // 2. Render the outbound message in the chat list immediately
        MessageModel outbound = new MessageModel(messageText, true /* sent */);
        java.util.List<MessageModel> updated = new java.util.ArrayList<>(
                chatAdapter.getCurrentList());
        updated.add(outbound);

        final RecyclerView chatList = findViewById(R.id.chatRecyclerView);

        // One-shot observer: scroll to bottom the moment items are inserted
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

        // 3. Show inline progress spinner on the send button
        showSendProgress(true);

        // 4. Dispatch to transport engine asynchronously
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
