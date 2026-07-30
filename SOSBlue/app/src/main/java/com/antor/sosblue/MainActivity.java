package com.antor.sosblue;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.antor.sosblue.identity.UserIdentity;
import com.antor.sosblue.inbox.ConversationAdapter;
import com.antor.sosblue.inbox.ConversationModel;
import com.antor.sosblue.inbox.ConversationRegistry;
import com.antor.sosblue.news.NewsFeedActivity;
import com.antor.sosblue.settings.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Clean conversation inbox — shows recent chats and discovered peers.
 * No transport controls, no composer — those live in ChatActivity.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private ConversationAdapter conversationAdapter;
    private TextView textStatus;
    private View searchOption;
    private EditText inputSearch;
    private List<ConversationModel> allConversations = new ArrayList<>();

    /** Permission launcher for POST_NOTIFICATIONS (Android 13+). */
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

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

        // ── Runtime permission launcher for POST_NOTIFICATIONS ──
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        Log.i(TAG, "POST_NOTIFICATIONS granted");
                    } else {
                        Log.w(TAG, "POST_NOTIFICATIONS denied");
                    }
                });
        requestNotificationPermissionIfNeeded();

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

        // ---------------------------------------------------------------
        //  Conversation RecyclerView (inbox)
        // ---------------------------------------------------------------

        RecyclerView conversationRecyclerView = findViewById(R.id.chatRecyclerView);
        conversationAdapter = new ConversationAdapter(conversation -> {
            // Open ChatActivity with the selected conversation's recipient
            Intent intent = new Intent(MainActivity.this, ChatActivity.class);
            intent.putExtra(ChatActivity.EXTRA_RECIPIENT_PHONE,
                    conversation.getConversationId());
            intent.putExtra(ChatActivity.EXTRA_RECIPIENT_NAME,
                    conversation.getDisplayName());
            ConversationRegistry.markRead(conversation.getConversationId());
            startActivity(intent);
        });
        conversationRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        conversationRecyclerView.setAdapter(conversationAdapter);

        // ---------------------------------------------------------------
        //  Views
        // ---------------------------------------------------------------

        textStatus = findViewById(R.id.textStatus);
        searchOption = findViewById(R.id.searchOption);
        inputSearch = findViewById(R.id.inputSearch);

        // ---------------------------------------------------------------
        //  Top action bar
        // ---------------------------------------------------------------

        // Search icon → Toggle inline search bar
        findViewById(R.id.searchIcon).setOnClickListener(v -> {
            if (searchOption.getVisibility() == View.VISIBLE) {
                searchOption.setVisibility(View.GONE);
            } else {
                searchOption.setVisibility(View.VISIBLE);
                inputSearch.requestFocus();
            }
        });

        findViewById(R.id.closeSearchOptionIcon).setOnClickListener(v -> {
            searchOption.setVisibility(View.GONE);
            inputSearch.setText("");
            // Restore full list
            allConversations = ConversationRegistry.getAll();
            conversationAdapter.submitList(new ArrayList<>(allConversations));
        });

        // Search input — real-time filter conversations
        inputSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().toLowerCase(java.util.Locale.ROOT).trim();
                List<ConversationModel> filtered = new ArrayList<>();
                if (query.isEmpty()) {
                    filtered.addAll(allConversations);
                } else {
                    for (ConversationModel conv : allConversations) {
                        if (conv.getDisplayName() != null
                                && conv.getDisplayName().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                            filtered.add(conv);
                        } else if (conv.getLastMessage() != null
                                && conv.getLastMessage().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                            filtered.add(conv);
                        }
                    }
                }
                conversationAdapter.submitList(filtered);
            }
        });

        // ── New Chat FAB → Open ChatActivity (empty, for fresh conversation)
        findViewById(R.id.newChatFab).setOnClickListener(v -> {
            try {
                startActivity(new Intent(MainActivity.this, ChatActivity.class));
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch ChatActivity from New Chat FAB", e);
            }
        });

        // RSS/feed icon → Open NewsFeedActivity
        findViewById(R.id.discoverIcon).setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, NewsFeedActivity.class));
        });

        // Overflow menu (3 dots)
        findViewById(R.id.threeDotIcon).setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(MainActivity.this, v);
            popup.getMenuInflater().inflate(R.menu.top_app_bar_menu, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.menu_news_feed) {
                    startActivity(new Intent(MainActivity.this, NewsFeedActivity.class));
                    return true;
                } else if (id == R.id.menu_settings) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    return true;
                }
                return false;
            });
            popup.show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConversationList();
    }

    @Override
    protected void onDestroy() {
        // Engine lifecycle managed by ChatActivity
        super.onDestroy();
    }

    // ---------------------------------------------------------------
    //  Conversation list
    // ---------------------------------------------------------------

    private void refreshConversationList() {
        allConversations = ConversationRegistry.getAll();
        conversationAdapter.submitList(new ArrayList<>(allConversations));

        // Update title with conversation count
        int count = allConversations.size();
        TextView titleView = findViewById(R.id.appTitle);
        if (titleView != null) {
            titleView.setText(count > 0 ? "Chats (" + count + ")" : "Chats");
        }

        // Show/hide empty state
        if (textStatus != null) {
            if (count == 0) {
                textStatus.setText("No conversations yet.\nTap a contact to start chatting.");
                textStatus.setVisibility(View.VISIBLE);
            } else {
                textStatus.setVisibility(View.GONE);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Runtime permission helpers
    // ---------------------------------------------------------------

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }
}
