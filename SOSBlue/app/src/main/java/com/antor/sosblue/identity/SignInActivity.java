package com.antor.sosblue.identity;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.antor.sosblue.ChatActivity;
import com.antor.sosblue.R;
import com.antor.sosblue.util.ToastUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Onboarding screen that captures the user's display name and phone number.
 *
 * <p>The phone number (E.164 format) serves as the unique peer identifier
 * for message targeting, address routing, and encryption key derivation
 * across the F2P mesh network.</p>
 */
public class SignInActivity extends AppCompatActivity {

    private TextInputEditText inputUsername;
    private TextInputEditText inputPhone;
    private TextView errorText;
    private MaterialButton btnSignIn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If already registered, skip straight to chat
        if (UserIdentity.isRegistered(this)) {
            launchChat();
            finish();
            return;
        }

        setContentView(R.layout.activity_sign_in);

        ViewCompatHelper.applyWindowInsets(findViewById(R.id.root));

        inputUsername = findViewById(R.id.inputUsername);
        inputPhone = findViewById(R.id.inputPhone);
        errorText = findViewById(R.id.errorText);
        btnSignIn = findViewById(R.id.btnSignIn);

        btnSignIn.setOnClickListener(v -> attemptSignIn());
    }

    // ---------------------------------------------------------------
    //  Sign-in logic
    // ---------------------------------------------------------------

    private void attemptSignIn() {
        String username = getInputText(inputUsername);
        String phone = getInputText(inputPhone);

        // Validate username
        if (TextUtils.isEmpty(username)) {
            showError("Please enter your display name");
            return;
        }
        if (username.length() < 2) {
            showError("Display name must be at least 2 characters");
            return;
        }

        // Validate phone (E.164)
        if (TextUtils.isEmpty(phone)) {
            showError("Please enter your phone number");
            return;
        }
        if (!MessageEncryptor.isValidE164(phone)) {
            showError("Invalid format. Use E.164: +8801XXXXXXXX (country code + number)");
            return;
        }

        // Persist identity
        UserIdentity.save(this, username, phone);

        ToastUtils.showShort(this, "Welcome, " + username + "!");
        launchChat();
        finish();
    }

    // ---------------------------------------------------------------
    //  Navigation
    // ---------------------------------------------------------------

    private void launchChat() {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    private static String getInputText(TextInputEditText editText) {
        if (editText == null || editText.getText() == null) return null;
        return editText.getText().toString().trim();
    }

    /**
     * Helper to apply window insets — extracted to keep onCreate clean.
     */
    private static class ViewCompatHelper {
        static void applyWindowInsets(View root) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                androidx.core.graphics.Insets systemBars =
                        insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });
        }
    }
}
