package com.antor.sosblue;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.antor.f2p.engine.api.EngineConfig;
import com.antor.sosblue.bridge.F2PBridge;
import com.antor.sosblue.bridge.TransportMode;
import com.antor.sosblue.ui.TransportSelectorView;

import com.google.android.material.snackbar.Snackbar;

public class MainActivity extends AppCompatActivity implements F2PBridge.SmsSender {

    private static final int SMS_PERMISSION_REQUEST_CODE = 1001;

    private F2PBridge bridge;
    private TransportSelectorView transportSelector;
    private TextView transportStatusHint;

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
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialise F2P Bridge
        bridge = new F2PBridge();

        // Start the engine with default config
        EngineConfig config = EngineConfig.builder()
                .nodeId("sosblue-" + System.currentTimeMillis())
                .build();
        bridge.startEngine(config);

        // Transport selector
        transportSelector = findViewById(R.id.transportSelector);
        transportStatusHint = findViewById(R.id.transportStatusHint);

        // Restore persisted preference
        TransportMode savedMode = TransportMode.load(this);
        transportSelector.setSelectedMode(savedMode);

        transportSelector.setOnModeSelectedListener(mode -> {
            mode.save(this);
            onTransportModeChanged(mode);
        });

        // Immediate check for initial mode
        onTransportModeChanged(transportSelector.getSelectedMode());

        // Wire send button
        findViewById(R.id.sendButton).setOnClickListener(v -> sendCurrentMessage());
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.stopEngine();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------
    //  Transport mode switching
    // ---------------------------------------------------------------

    private void onTransportModeChanged(TransportMode mode) {
        if (transportStatusHint == null) return;

        switch (mode) {
            case SOSBLUE_MESH:
                transportStatusHint.setText("Mesh P2P");
                transportStatusHint.setTextColor(0xFF0D80E0);
                break;

            case F2P_SERVERLESS:
                if (bridge.isRouting()) {
                    transportStatusHint.setText("F2P Serverless");
                    transportStatusHint.setTextColor(0xFF0D80E0);
                } else {
                    transportStatusHint.setText("F2P offline");
                    transportStatusHint.setTextColor(0xFFE67E22);  // amber warning

                    // Show Snackbar with fallback options
                    View root = findViewById(R.id.main);
                    Snackbar sb = Snackbar.make(root, "F2P engine not connected",
                            Snackbar.LENGTH_LONG);
                    sb.setAction("Use Mesh", v -> {
                        transportSelector.setSelectedMode(TransportMode.SOSBLUE_MESH);
                        transportSelector.getSelectedMode().save(this);
                    });
                    sb.show();
                }
                break;

            case SMS:
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                        != PackageManager.PERMISSION_GRANTED) {
                    transportStatusHint.setText("SMS (grant permission)");
                    transportStatusHint.setTextColor(0xFFE74C3C);  // red

                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.SEND_SMS},
                            SMS_PERMISSION_REQUEST_CODE);
                } else {
                    transportStatusHint.setText("SMS");
                    transportStatusHint.setTextColor(0xFF27AE60);  // green
                }
                break;
        }
    }

    // ---------------------------------------------------------------
    //  Send message (called from send button / IME action)
    // ---------------------------------------------------------------

    private void sendCurrentMessage() {
        String messageText = String.valueOf(
                ((android.widget.EditText) findViewById(R.id.inputMessage)).getText()
        ).trim();
        if (messageText.isEmpty()) return;

        String recipientId = bridge.getLocalNodeId(); // or from UI
        TransportMode mode = transportSelector.getSelectedMode();

        boolean accepted = bridge.sendMessage(messageText, recipientId, mode, this);

        if (accepted) {
            ((android.widget.EditText) findViewById(R.id.inputMessage)).setText("");
        } else {
            Toast.makeText(this, "Failed to send via " + mode.getLabel(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------
    //  SMS permission result
    // ---------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == SMS_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "SMS permission granted", Toast.LENGTH_SHORT).show();
                onTransportModeChanged(TransportMode.SMS);
            } else {
                Toast.makeText(this, "SMS permission denied — falling back to SOSBlue Mesh",
                        Toast.LENGTH_LONG).show();
                transportSelector.setSelectedMode(TransportMode.SOSBLUE_MESH);
            }
        }
    }

    // ---------------------------------------------------------------
    //  F2PBridge.SmsSender implementation
    // ---------------------------------------------------------------

    @Override
    public void sendSms(String phoneNumber, String message) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "SMS permission not granted", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SmsManager sms = SmsManager.getDefault();
            sms.sendTextMessage(phoneNumber, null, message, null, null);
            Toast.makeText(this, "SMS sent to " + phoneNumber, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "SMS failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
