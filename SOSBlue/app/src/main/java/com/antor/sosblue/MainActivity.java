package com.antor.sosblue;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

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
import com.antor.sosblue.inbox.ConversationAdapter;
import com.antor.sosblue.inbox.ConversationModel;
import com.antor.sosblue.inbox.ConversationRegistry;

import com.antor.sosblue.util.ToastUtils;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {

    private F2PBridge bridge;
    private RadioGroup transportRadioGroup;
    private TextView textStatus;
    private ConversationAdapter conversationAdapter;
    private View loadingContainer;

    // Peer discovery
    private View peerPanel;
    private RecyclerView peerRecyclerView;
    private TextView peerEmptyHint;
    private TextView peerCountLabel;
    private PeerDiscoveryAdapter peerAdapter;
    private boolean peerPanelVisible;
    private boolean engineReady;

    private RecyclerView conversationRecyclerView;

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
                if (phone != null) {
                    peerPhoneNumbers.put(id, phone);
                    // Also register display name in notification cache
                    com.antor.sosblue.notification.NotificationHelper
                            .registerDisplayName(phone, displayName);
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

        // ---------------------------------------------------------------
        //  Conversation RecyclerView (inbox)
        // ---------------------------------------------------------------

        conversationRecyclerView = findViewById(R.id.chatRecyclerView);
        conversationAdapter = new ConversationAdapter(conversation -> {
            // Open ChatActivity with the selected conversation's recipient
            Intent intent = new Intent(MainActivity.this, ChatActivity.class);
            intent.putExtra(ChatActivity.EXTRA_RECIPIENT_PHONE,
                    conversation.getConversationId());
            intent.putExtra(ChatActivity.EXTRA_RECIPIENT_NAME,
                    conversation.getDisplayName());
            // Mark as read
            ConversationRegistry.markRead(conversation.getConversationId());
            startActivity(intent);
        });
        conversationRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        conversationRecyclerView.setAdapter(conversationAdapter);

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

        // Initial status display
        onTransportModeChanged(
                radioIdToTransportMode(transportRadioGroup.getCheckedRadioButtonId()));

        // ---------------------------------------------------------------
        //  Hide previously used chat input elements (inbox has no composer)
        // ---------------------------------------------------------------

        View inputContainer = findViewById(R.id.inputContainer);
        if (inputContainer != null) inputContainer.setVisibility(View.GONE);
        View replyPreview = findViewById(R.id.replyPreviewContainer);
        if (replyPreview != null) replyPreview.setVisibility(View.GONE);

        // ---------------------------------------------------------------
        //  Top action bar
        // ---------------------------------------------------------------

        // Search icon → Toggle search bar visibility
        findViewById(R.id.searchIcon).setOnClickListener(v -> {
            View searchOption = findViewById(R.id.searchOption);
            if (searchOption != null) {
                boolean visible = searchOption.getVisibility() == View.VISIBLE;
                searchOption.setVisibility(visible ? View.GONE : View.VISIBLE);
                if (!visible) {
                    EditText searchField = findViewById(R.id.inputSearch);
                    if (searchField != null) searchField.requestFocus();
                }
            }
        });

        findViewById(R.id.closeSearchOptionIcon).setOnClickListener(v -> {
            View searchOption = findViewById(R.id.searchOption);
            if (searchOption != null) searchOption.setVisibility(View.GONE);
        });

        // Broadcast (RSS/feed) icon → Open NewsFeedActivity
        findViewById(R.id.discoverIcon).setOnClickListener(v -> {
            Intent newsIntent = new Intent(MainActivity.this,
                    com.antor.sosblue.news.NewsFeedActivity.class);
            startActivity(newsIntent);
        });

        // Overflow menu (3 dots)
        findViewById(R.id.threeDotIcon).setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(MainActivity.this, v);
            popup.getMenuInflater().inflate(R.menu.top_app_bar_menu, popup.getMenu());

            // Mark the current transport mode as checked
            TransportMode currentMode = TransportMode.load(MainActivity.this);
            switch (currentMode) {
                case F2P_SERVERLESS:
                    popup.getMenu().findItem(R.id.menu_transport_f2p).setChecked(true);
                    break;
                case SMS_FALLBACK:
                    popup.getMenu().findItem(R.id.menu_transport_sms).setChecked(true);
                    break;
                default:
                    popup.getMenu().findItem(R.id.menu_transport_mesh).setChecked(true);
                    break;
            }

            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.menu_chats) {
                    startActivity(new Intent(MainActivity.this, ChatActivity.class));
                    finish();
                    return true;
                } else if (id == R.id.menu_news_feed) {
                    startActivity(new Intent(MainActivity.this,
                            com.antor.sosblue.news.NewsFeedActivity.class));
                    finish();
                    return true;
                } else if (id == R.id.menu_transport_mesh) {
                    TransportMode.SOSBLUE_MESH.save(MainActivity.this);
                    transportRadioGroup.check(R.id.rb_sosblue_mesh);
                    onTransportModeChanged(TransportMode.SOSBLUE_MESH);
                    ToastUtils.showShort(MainActivity.this,
                            "Switched to " + TransportMode.SOSBLUE_MESH.getLabel());
                    return true;
                } else if (id == R.id.menu_transport_f2p) {
                    TransportMode.F2P_SERVERLESS.save(MainActivity.this);
                    transportRadioGroup.check(R.id.rb_f2p_serverless);
                    onTransportModeChanged(TransportMode.F2P_SERVERLESS);
                    ToastUtils.showShort(MainActivity.this,
                            "Switched to " + TransportMode.F2P_SERVERLESS.getLabel());
                    return true;
                } else if (id == R.id.menu_transport_sms) {
                    if (!TransportMode.SMS_FALLBACK.isAvailable(MainActivity.this)) {
                        ToastUtils.showShort(MainActivity.this,
                                R.string.transport_sms_unavailable);
                        return true;
                    }
                    TransportMode.SMS_FALLBACK.save(MainActivity.this);
                    transportRadioGroup.check(R.id.rb_sms_fallback);
                    onTransportModeChanged(TransportMode.SMS_FALLBACK);
                    ToastUtils.showShort(MainActivity.this,
                            "Switched to " + TransportMode.SMS_FALLBACK.getLabel());
                    return true;
                } else if (id == R.id.menu_settings) {
                    startActivity(new Intent(MainActivity.this,
                            com.antor.sosblue.settings.SettingsActivity.class));
                    return true;
                } else if (id == R.id.menu_about) {
                    showAboutDialog();
                    return true;
                }
                return false;
            });
            popup.show();
        });

        // ---------------------------------------------------------------
        //  Peer discovery panel
        // ---------------------------------------------------------------

        peerPanel = findViewById(R.id.peerDiscoveryPanel);
        peerRecyclerView = findViewById(R.id.peerRecyclerView);
        peerEmptyHint = findViewById(R.id.peerEmptyHint);
        peerCountLabel = findViewById(R.id.peerCountLabel);
        Button peerRefreshButton = findViewById(R.id.peerRefreshButton);

        peerAdapter = new PeerDiscoveryAdapter(new ArrayList<>(), peer -> {
            // Launch ChatActivity with the selected peer
            Intent intent = new Intent(MainActivity.this, ChatActivity.class);
            String peerId = peer.getId();
            String peerPhone = peerPhoneNumbers.get(peerId);
            if (peerPhone != null) {
                intent.putExtra(ChatActivity.EXTRA_RECIPIENT_PHONE, peerPhone);
            } else {
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

        peerRefreshButton.setOnClickListener(v -> refreshPeerList());

        // ---------------------------------------------------------------
        //  Start engine
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
    protected void onResume() {
        super.onResume();
        // Refresh the conversation list every time we return to MainActivity
        refreshConversationList();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Activity going to background — resources are released in onDestroy
    }

    @Override
    protected void onDestroy() {
        try {
            if (bridge != null) {
                bridge.stopEngine();
                bridge = null;
            }
        } catch (Exception e) {
            Log.w("MainActivity", "Error stopping engine in onDestroy", e);
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------
    //  Conversation list
    // ---------------------------------------------------------------

    private void refreshConversationList() {
        List<ConversationModel> conversations = ConversationRegistry.getAll();
        conversationAdapter.submitList(conversations);

        // Update title with conversation count
        int count = conversations.size();
        TextView titleView = findViewById(R.id.appTitle);
        if (titleView != null) {
            titleView.setText(count > 0 ? "Chats (" + count + ")" : "Chats");
        }

        // Show/hide empty state (only after engine is ready)
        if (textStatus != null && engineReady) {
            if (count == 0) {
                textStatus.setText("No conversations yet.\nTap a peer to start chatting.");
                textStatus.setVisibility(View.VISIBLE);
            } else {
                textStatus.setVisibility(View.GONE);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static TransportMode radioIdToTransportMode(int radioId) {
        if (radioId == R.id.rb_f2p_serverless) {
            return TransportMode.F2P_SERVERLESS;
        } else if (radioId == R.id.rb_sms_fallback) {
            return TransportMode.SMS_FALLBACK;
        }
        return TransportMode.SOSBLUE_MESH;
    }

    private void showLoading(boolean show) {
        if (textStatus != null) {
            textStatus.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                textStatus.setText("Initialising engine...");
            }
        }
    }

    // ---------------------------------------------------------------
    //  Peer discovery panel
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

        int activeCount = engineReady ? bridge.getActiveNodeCount() : 0;
        peerCountLabel.setText(devices.size() + " peer(s) discovered · "
                + activeCount + " online");
        peerEmptyHint.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------
    //  Transport mode switching
    // ---------------------------------------------------------------

    private void onTransportModeChanged(TransportMode mode) {
        if (isFinishing() || isDestroyed()) return;

        if (mode == TransportMode.SOSBLUE_MESH) {
            if (engineReady && bridge != null) {
                refreshPeerList();
                showPeerPanel(true);
            }
        } else {
            showPeerPanel(false);
            if (bridge != null && !bridge.isRouting()) {
                View root = findViewById(R.id.main);
                if (root == null) return;
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
    //  Dialogs
    // ---------------------------------------------------------------

    private void showAboutDialog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("About SOSBlue")
                .setMessage("SOSBlue — Secure Offline-Safe Blue Messenger\n\n"
                        + "Version 1.0\n\n"
                        + "A peer-to-peer messaging app with 3-tier transport:\n"
                        + "\u2022 SOSBlue Mesh (BLE/WiFi-Direct P2P)\n"
                        + "\u2022 F2P Serverless (WanderingFibreEngine)\n"
                        + "\u2022 SMS Relay (carrier fallback)\n\n"
                        + "All messages are end-to-end encrypted.")
                .setPositiveButton("OK", null)
                .show();
    }
}
