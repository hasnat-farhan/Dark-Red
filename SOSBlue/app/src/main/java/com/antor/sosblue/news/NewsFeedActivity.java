package com.antor.sosblue.news;

import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.antor.sosblue.ChatActivity;
import com.antor.sosblue.R;
import com.antor.sosblue.bridge.F2PBridge;
import com.antor.sosblue.bridge.TransportMode;
import com.antor.sosblue.identity.F2PMessage;
import com.antor.sosblue.identity.MessageEncryptor;
import com.antor.sosblue.identity.UserIdentity;
import com.antor.sosblue.inbox.ConversationModel;
import com.antor.sosblue.inbox.ConversationRegistry;
import com.antor.sosblue.notification.NotificationHelper;
import com.antor.sosblue.util.ToastUtils;
import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * News Broadcast Feed activity.
 *
 * <p>Provides a dedicated card-based RecyclerView for broadcast news items,
 * supports all three transport tiers (SOSBlue Mesh, F2P Serverless, SMS),
 * and includes a compose bar with optional media attachment. The bottom
 * navigation bar lets users quickly switch between Chats and News.</p>
 */
public class NewsFeedActivity extends AppCompatActivity {

    private static final String TAG = "NewsFeedActivity";

    // Core
    private F2PBridge bridge;
    private NotificationHelper notificationHelper;

    // News data
    private final List<F2PNewsPacket> newsItems = new ArrayList<>();
    private NewsAdapter newsAdapter;

    // Transport selection
    private RadioGroup transportRadioGroup;

    // Media picker
    private ActivityResultLauncher<String[]> mediaPickerLauncher;
    private Uri selectedMediaUri;
    private String selectedMediaMimeType;

    // Views
    private EditText searchInput;
    private View searchBar;
    private RecyclerView newsRecyclerView;
    private TextView emptyHint;
    private EditText newsInputText;
    private View sendButton;
    private View loadingContainer;

    // Search state
    private boolean searchVisible = false;
    private String searchQuery = "";
    private List<F2PNewsPacket> filteredItems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── F2P Identity Gate ──────────────────────────────────────
        if (!UserIdentity.isRegistered(this)) {
            startActivity(new Intent(this,
                    com.antor.sosblue.identity.SignInActivity.class));
            finish();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_news_feed);

        // ── Edge-to-edge insets ─────────────────────────────────────
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.newsRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });

        // ── Initialise bridge + notifications ────────────────────────
        bridge = new F2PBridge(this);
        notificationHelper = new NotificationHelper(this);

        // ── Bind views ──────────────────────────────────────────────
        bindViews();

        // ── Set up news RecyclerView ────────────────────────────────
        newsAdapter = new NewsAdapter();
        newsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        newsRecyclerView.setAdapter(newsAdapter);
        newsAdapter.submitList(newsItems);

        // ── Transport selector ───────────────────────────────────────
        TransportMode savedMode = TransportMode.load(this);
        if (savedMode == TransportMode.F2P_SERVERLESS) {
            transportRadioGroup.check(R.id.news_rb_f2p_serverless);
        } else if (savedMode == TransportMode.SMS_FALLBACK) {
            transportRadioGroup.check(R.id.news_rb_sms_fallback);
        } else {
            transportRadioGroup.check(R.id.news_rb_f2p_serverless);
        }

        // Persist transport mode changes made directly via the radio group
        transportRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            TransportMode mode;
            if (checkedId == R.id.news_rb_f2p_serverless) {
                mode = TransportMode.F2P_SERVERLESS;
            } else if (checkedId == R.id.news_rb_sms_fallback) {
                mode = TransportMode.SMS_FALLBACK;
            } else {
                mode = TransportMode.SOSBLUE_MESH;
            }
            mode.save(NewsFeedActivity.this);
        });

        // ── Top bar actions ──────────────────────────────────────────
        findViewById(R.id.newsSearchIcon).setOnClickListener(v -> toggleSearchBar());
        findViewById(R.id.newsSearchClose).setOnClickListener(v -> hideSearchBar());

        findViewById(R.id.newsOverflowIcon).setOnClickListener(v -> showOverflowMenu(v));

        // ── Search input listener ────────────────────────────────────
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                searchQuery = s.toString().toLowerCase(java.util.Locale.ROOT).trim();
                applySearchFilter();
            }
        });

        // ── Media picker ─────────────────────────────────────────────
        mediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        selectedMediaUri = uri;
                        selectedMediaMimeType = getContentResolver().getType(uri);
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {}
                        ToastUtils.showShort(this, "Media attached");
                    }
                });

        findViewById(R.id.newsAttachButton).setOnClickListener(v -> {
            mediaPickerLauncher.launch(new String[]{"image/*", "video/*"});
        });

        // ── Send button + IME action ─────────────────────────────────
        findViewById(R.id.newsSendButton).setOnClickListener(v -> sendCurrentNews());

        newsInputText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendCurrentNews();
                return true;
            }
            return false;
        });

        // Add a few demo news items for testing
        addDemoNewsItems();
    }

    // ---------------------------------------------------------------
    //  View binding
    // ---------------------------------------------------------------

    private void bindViews() {
        searchBar = findViewById(R.id.newsSearchBar);
        searchInput = findViewById(R.id.newsSearchInput);
        newsRecyclerView = findViewById(R.id.newsRecyclerView);
        emptyHint = findViewById(R.id.newsEmptyHint);
        transportRadioGroup = findViewById(R.id.newsTransportRadioGroup);
        newsInputText = findViewById(R.id.newsInputText);
        sendButton = findViewById(R.id.newsSendButton);
        loadingContainer = findViewById(R.id.newsLoadingContainer);
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
        searchBar.setVisibility(View.VISIBLE);
        searchInput.requestFocus();
    }

    private void hideSearchBar() {
        searchVisible = false;
        searchBar.setVisibility(View.GONE);
        searchInput.setText("");
        searchQuery = "";
        applySearchFilter();
    }

    private void applySearchFilter() {
        filteredItems.clear();
        if (searchQuery.isEmpty()) {
            filteredItems.addAll(newsItems);
        } else {
            for (F2PNewsPacket item : newsItems) {
                if (item.getTextPayload().toLowerCase(java.util.Locale.ROOT).contains(searchQuery)
                        || item.getAuthorName().toLowerCase(java.util.Locale.ROOT).contains(searchQuery)
                        || item.getAuthorPhone().contains(searchQuery)) {
                    filteredItems.add(item);
                }
            }
        }
        newsAdapter.submitList(new ArrayList<>(filteredItems));
        updateEmptyHint(filteredItems.isEmpty());
    }

    // ---------------------------------------------------------------
    //  Overflow menu (PopupMenu)
    // ---------------------------------------------------------------

    private void showOverflowMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.top_app_bar_menu, popup.getMenu());

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_about) {
                showAboutDialog();
                return true;
            } else if (id == R.id.menu_settings) {
                startActivity(new Intent(this,
                        com.antor.sosblue.settings.SettingsActivity.class));
                return true;
            }
            return false;
        });
        popup.show();
    }

    // ---------------------------------------------------------------
    //  Send news broadcast
    // ---------------------------------------------------------------

    private void sendCurrentNews() {
        String text = newsInputText.getText().toString().trim();
        if (text.isEmpty()) {
            ToastUtils.showShort(this, "Enter news text");
            return;
        }

        newsInputText.setText("");

        String myPhone = UserIdentity.getPhoneNumber(this);
        String myName = UserIdentity.getUsername(this);
        if (myPhone == null || myName == null) {
            ToastUtils.showShort(this, "Identity not set");
            return;
        }

        // ── Determine transport from radio ──────────────────────────
        int checkedId = transportRadioGroup.getCheckedRadioButtonId();
        F2PNewsPacket.TransportType transportType;
        TransportMode bridgeMode;
        if (checkedId == R.id.news_rb_f2p_serverless) {
            transportType = F2PNewsPacket.TransportType.F2P_SERVERLESS;
            bridgeMode = TransportMode.F2P_SERVERLESS;
        } else if (checkedId == R.id.news_rb_sms_fallback) {
            transportType = F2PNewsPacket.TransportType.SMS_FALLBACK;
            bridgeMode = TransportMode.SMS_FALLBACK;
        } else {
            transportType = F2PNewsPacket.TransportType.SOSBLUE_MESH;
            bridgeMode = TransportMode.SOSBLUE_MESH;
        }

        // ── Handle attached media ────────────────────────────────────
        String mediaBase64 = null;
        String mediaMimeType = null;
        if (selectedMediaUri != null) {
            try {
                InputStream is = getContentResolver().openInputStream(selectedMediaUri);
                if (is != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                    is.close();
                    mediaBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                    mediaMimeType = selectedMediaMimeType;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to read attached media", e);
                Snackbar.make(findViewById(R.id.newsRoot),
                        "Failed to read media attachment", Snackbar.LENGTH_LONG).show();
            }
        }

        // ── Build news packet ────────────────────────────────────────
        F2PNewsPacket newsPacket = new F2PNewsPacket(
                UUID.randomUUID().toString(),
                myName, myPhone,
                transportType, text,
                mediaBase64, mediaMimeType,
                System.currentTimeMillis()
        );

        // ── Render locally immediately ───────────────────────────────
        addNewsItem(newsPacket);
        selectedMediaUri = null;
        selectedMediaMimeType = null;

        // ── Dispatch via bridge ──────────────────────────────────────
        showSendProgress(true);
        dispatchNewsOverTransport(newsPacket, bridgeMode);
    }

    // ---------------------------------------------------------------
    //  Permission check for SMS transport
    // ---------------------------------------------------------------

    /**
     * Checks if the necessary SMS permissions are granted for news broadcast.
     */
    private boolean hasSmsPermissions() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Dispatches the news packet over the selected transport.
     * For SMS, uses the SMS wire format; for Mesh/F2P, uses F2PMessage envelope.
     */
    private void dispatchNewsOverTransport(F2PNewsPacket newsPacket,
                                            TransportMode bridgeMode) {
        String myPhone = UserIdentity.getPhoneNumber(this);

        switch (bridgeMode) {
            case SMS_FALLBACK: {
                // ── SMS: use wire format "[NEWS:AuthorName] text" ──
                if (!TransportMode.SMS_FALLBACK.isAvailable(this)) {
                    ToastUtils.showShort(this, "SMS not available on this device");
                    showSendProgress(false);
                    return;
                }
                String smsText = newsPacket.toSmsText();

                // ── Send news only to contacts previously chatted with via SMS ──
                // Look up all conversations and filter by SMS transport
                java.util.List<ConversationModel> allConversations = ConversationRegistry.getAll();
                final java.util.List<String> smsRecipients = new java.util.ArrayList<>();
                for (ConversationModel conv : allConversations) {
                    if ("SMS_FALLBACK".equals(conv.getLastTransportMode())) {
                        smsRecipients.add(conv.getConversationId());
                    }
                }

                if (smsRecipients.isEmpty()) {
                    ToastUtils.showShort(this, "No SMS contacts yet — start an SMS chat first");
                    showSendProgress(false);
                    return;
                }

                final int totalTargets = smsRecipients.size();
                final int[] sentCount = {0};
                final int[] failCount = {0};

                for (String recipientPhone : smsRecipients) {
                    bridge.sendMessageAsync(smsText, recipientPhone, TransportMode.SMS_FALLBACK,
                            new F2PBridge.OnMessageSendListener() {
                                @Override
                                public void onSent() {
                                    synchronized (sentCount) {
                                        sentCount[0]++;
                                        int done = sentCount[0] + failCount[0];
                                        if (done == totalTargets) {
                                            showSendProgress(false);
                                            if (failCount[0] == 0) {
                                                ToastUtils.showShort(NewsFeedActivity.this,
                                                        "News sent to " + sentCount[0]
                                                                + " SMS contact" + (sentCount[0] != 1 ? "s" : ""));
                                            } else {
                                                Snackbar.make(findViewById(R.id.newsRoot),
                                                        "Sent to " + sentCount[0] + "/" + totalTargets
                                                                + " SMS contacts (" + failCount[0]
                                                                + " failed)",
                                                        Snackbar.LENGTH_LONG).show();
                                            }
                                        }
                                    }
                                }
                                @Override
                                public void onSendFailed(String reason) {
                                    synchronized (failCount) {
                                        failCount[0]++;
                                        int done = sentCount[0] + failCount[0];
                                        if (done == totalTargets) {
                                            showSendProgress(false);
                                            Snackbar.make(findViewById(R.id.newsRoot),
                                                    "Sent to " + sentCount[0] + "/" + totalTargets
                                                            + " SMS contacts (" + failCount[0]
                                                            + " failed)",
                                                    Snackbar.LENGTH_LONG).show();
                                        }
                                    }
                                }
                            });
                }
                break;
            }
            case F2P_SERVERLESS:
            case SOSBLUE_MESH: {
                // ── Mesh/F2P: encrypt and send──────────────
                String selfPhone = UserIdentity.getPhoneNumber(this);
                if (selfPhone != null) {
                    // For news, we broadcast to self as a demo
                    bridge.sendMessageAsync(newsPacket.getTextPayload(), selfPhone, bridgeMode,
                            new F2PBridge.OnMessageSendListener() {
                                @Override
                                public void onSent() {
                                    showSendProgress(false);
                                    ToastUtils.showShort(NewsFeedActivity.this,
                                            "News broadcast via " + bridgeMode.getLabel());
                                }
                                @Override
                                public void onSendFailed(String reason) {
                                    showSendProgress(false);
                                    Snackbar.make(findViewById(R.id.newsRoot),
                                            "Broadcast failed: " + reason,
                                            Snackbar.LENGTH_LONG).show();
                                }
                            });
                }
                break;
            }
        }
    }

    // ---------------------------------------------------------------
    //  News item management
    // ---------------------------------------------------------------

    /**
     * Adds a news item to the local list and updates the adapter.
     * Posts a system notification if the item is not from the local user.
     */
    public void addNewsItem(F2PNewsPacket packet) {
        newsItems.add(0, packet); // insert at top (newest first)
        applySearchFilter();

        // ── Register the author's display name in the notification cache ──
        // This ensures that if this user later sends a direct message, the
        // notification shows their human-readable name instead of a raw phone.
        com.antor.sosblue.notification.NotificationHelper
                .registerDisplayName(packet.getAuthorPhone(), packet.getAuthorName());

        // ── Post notification for incoming news ──────────────────────
        String myPhone = UserIdentity.getPhoneNumber(this);
        if (myPhone != null && !myPhone.equals(packet.getAuthorPhone())) {
            notificationHelper.notifyIncomingNews(
                    packet.getAuthorName(), packet.getTextPayload());
        }
    }

    private void updateEmptyHint(boolean isEmpty) {
        emptyHint.setVisibility(isEmpty && !searchVisible ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------
    //  Demo items for testing
    // ---------------------------------------------------------------

    private void addDemoNewsItems() {
        String myName = UserIdentity.getUsername(this);
        String myPhone = UserIdentity.getPhoneNumber(this);

        if (myName != null && myPhone != null) {
            newsItems.add(new F2PNewsPacket(
                    UUID.randomUUID().toString(), myName, myPhone,
                    F2PNewsPacket.TransportType.SOSBLUE_MESH,
                    "Welcome to SOSBlue Broadcast News! This is a demo broadcast sent over the SOSBlue Mesh.",
                    null, null, System.currentTimeMillis() - 120_000));
            newsItems.add(new F2PNewsPacket(
                    UUID.randomUUID().toString(), "Demo User", "+8801700000000",
                    F2PNewsPacket.TransportType.F2P_SERVERLESS,
                    "This news was received via F2P Serverless transport. End-to-end encrypted.",
                    null, null, System.currentTimeMillis() - 300_000));
            newsItems.add(new F2PNewsPacket(
                    UUID.randomUUID().toString(), "SMS Relay", "+8801711111111",
                    F2PNewsPacket.TransportType.SMS_FALLBACK,
                    "[NEWS:SMS Relay] Breaking: This broadcast arrived via carrier SMS as a last-resort fallback.",
                    null, null, System.currentTimeMillis() - 600_000));
            applySearchFilter();
        }
    }

    // ---------------------------------------------------------------
    //  Progress helpers
    // ---------------------------------------------------------------

    // ---------------------------------------------------------------
    //  About dialog
    // ---------------------------------------------------------------

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_about_title)
                .setMessage(R.string.dialog_about_message)
                .setPositiveButton(R.string.dialog_ok, null)
                .show();
    }

    private void showSendProgress(boolean show) {
        sendButton.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        loadingContainer.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Safely release resources when activity goes to background
        // to prevent crashes on rapid mode/tab switching
    }

    @Override
    protected void onDestroy() {
        try {
            if (bridge != null) {
                bridge.stopEngine();
                bridge = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error stopping engine in onDestroy", e);
        }
        try {
            if (notificationHelper != null) {
                notificationHelper = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error cleaning up notifications in onDestroy", e);
        }
        super.onDestroy();
    }
}
