package com.antor.sosblue.settings;

import android.content.Intent;
import android.os.Bundle;
import android.widget.RadioGroup;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.antor.sosblue.R;
import com.antor.sosblue.bridge.TransportMode;
import com.antor.sosblue.util.ToastUtils;

/**
 * Settings screen for configuring transport mode, notification preferences,
 * and viewing app information.
 *
 * <p>Layout is a scrollable card-based dark-theme screen with three sections:</p>
 * <ol>
 *   <li><b>Transport Mode</b> — radio group selecting SOSBlue Mesh / F2P Serverless / SMS Relay</li>
 *   <li><b>Notifications</b> — toggle switches for chat and news broadcast notifications</li>
 *   <li><b>About</b> — app name, version, description of the 3-tier transport system</li>
 * </ol>
 */
public class SettingsActivity extends AppCompatActivity {

    private RadioGroup transportRadioGroup;
    private SettingsManager settingsManager;
    private androidx.appcompat.widget.SwitchCompat switchChat;
    private androidx.appcompat.widget.SwitchCompat switchNews;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        // Edge-to-edge insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });

        settingsManager = new SettingsManager(this);

        // ── Back button ────────────────────────────────────────────
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // ── Transport mode radio group ──────────────────────────────
        transportRadioGroup = findViewById(R.id.transportRadioGroup);

        // Restore persisted transport mode
        TransportMode savedMode = TransportMode.load(this);
        switch (savedMode) {
            case F2P_SERVERLESS:
                transportRadioGroup.check(R.id.rb_f2p_serverless);
                break;
            case SMS_FALLBACK:
                transportRadioGroup.check(R.id.rb_sms_fallback);
                break;
            default:
                transportRadioGroup.check(R.id.rb_sosblue_mesh);
                break;
        }

        transportRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            TransportMode mode = radioIdToTransportMode(checkedId);
            // Check availability
            if (mode == TransportMode.SMS_FALLBACK && !mode.isAvailable(this)) {
                ToastUtils.showShort(this, R.string.transport_sms_unavailable);
                // Revert to previously saved mode
                TransportMode previous = TransportMode.load(this);
                switch (previous) {
                    case F2P_SERVERLESS:
                        transportRadioGroup.check(R.id.rb_f2p_serverless);
                        break;
                    default:
                        transportRadioGroup.check(R.id.rb_sosblue_mesh);
                        break;
                }
                return;
            }
            mode.save(this);
            ToastUtils.showShort(this, "Switched to " + mode.getLabel());
        });

        // ── Notification toggles ────────────────────────────────────
        switchChat = findViewById(R.id.switchChatNotifications);
        switchNews = findViewById(R.id.switchNewsNotifications);

        // Restore saved states
        switchChat.setChecked(settingsManager.isChatNotificationEnabled());
        switchNews.setChecked(settingsManager.isNewsNotificationEnabled());

        switchChat.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsManager.setChatNotificationEnabled(isChecked);
        });

        switchNews.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsManager.setNewsNotificationEnabled(isChecked);
        });

        // ── Reset to defaults ───────────────────────────────────────
        findViewById(R.id.btnResetDefaults).setOnClickListener(v -> {
            settingsManager.resetToDefaults();
            switchChat.setChecked(true);
            switchNews.setChecked(true);
            // Reset transport to SOSBlue Mesh (default)
            transportRadioGroup.check(R.id.rb_sosblue_mesh);
            TransportMode.SOSBLUE_MESH.save(this);
            ToastUtils.showShort(this, "Settings reset to defaults");
        });
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
}
