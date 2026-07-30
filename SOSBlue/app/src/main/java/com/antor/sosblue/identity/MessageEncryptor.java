package com.antor.sosblue.identity;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encryption module that derives symmetric keys from phone numbers.
 *
 * <p><strong>Key derivation:</strong> SHA-256(phone_number) → 256-bit AES key.<br>
 * <strong>Cipher:</strong> AES-256-GCM (128-bit tag, 12-byte IV, no padding).</p>
 *
 * <p>Each unique phone number produces a unique key. The sender encrypts
 * with the recipient's phone-derived key; only the recipient (who knows
 * the same phone number) can derive the same key and decrypt.</p>
 *
 * <p><strong>Encrypted payload format:</strong><br>
 * {@code [IV (12 bytes)] [ciphertext (variable)] [GCM tag (16 bytes)]}</p>
 */
public final class MessageEncryptor {

    private static final String TAG = "MessageEncryptor";
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;   // bits
    private static final int IV_LENGTH = 12;          // bytes
    private static final int NONCE_LENGTH = 16;       // bytes

    /** Cache derived keys to avoid recomputing SHA-256 on every message. */
    private static final ConcurrentHashMap<String, SecretKeySpec> keyCache =
            new ConcurrentHashMap<>();

    private MessageEncryptor() {}

    // ---------------------------------------------------------------
    //  Key derivation
    // ---------------------------------------------------------------

    /**
     * Derives an AES-256 key from a phone number using SHA-256.
     *
     * <p><strong>Key-derivation normalization:</strong> The leading {@code '+'}
     * is stripped before hashing so that both {@code "+8801712345678"} and
     * {@code "8801712345678"} produce the same key. This guarantees the sender
     * (who encrypts with the <em>recipient</em> phone) and the receiver (who
     * decrypts with their <em>own</em> phone) compute identical keys even when
     * one side includes the leading {@code '+'} and the other does not.</p>
     *
     * @param phoneNumber the phone number (E.164 format, with or without leading '+')
     * @return AES secret key specification
     */
    public static SecretKeySpec deriveKey(String phoneNumber) {
        if (phoneNumber == null) {
            throw new NullPointerException("phoneNumber must not be null");
        }

        // ── Normalize: strip all non-digit chars except leading '+' ──
        // This mirrors UserIdentity.normalizePhoneNumber() to handle any
        // formatted input (spaces, dashes, parentheses, dots) that may
        // bypass the normalizer and still produce a consistent SHA-256 key.
        String normalized;
        if (phoneNumber.startsWith("+")) {
            // Keep the leading '+', strip everything else that is not a digit
            normalized = "+" + phoneNumber.substring(1).replaceAll("[^\\d]", "");
        } else {
            normalized = phoneNumber.replaceAll("[^\\d]", "");
        }

        // Strip leading '+' for SHA-256 hashing so both
        // "+8801712345678" and "8801712345678" derive the exact same key.
        // The cache key is the cleaned (stripped) form.
        String cleanPhone = normalized.startsWith("+") ? normalized.substring(1) : normalized;

        return keyCache.computeIfAbsent(cleanPhone, phone -> {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(phone.getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(hash, "AES");
            } catch (Exception e) {
                throw new RuntimeException("Failed to derive key for phone: " + phone, e);
            }
        });
    }

    // ---------------------------------------------------------------
    //  Encryption
    // ---------------------------------------------------------------

    /**
     * Encrypts a plaintext message for a specific recipient.
     *
     * @param recipientPhone the recipient's phone number (used as key context)
     * @param plaintext      the raw message bytes
     * @return encrypted payload: {@code [IV (12)] [ciphertext] [GCM tag (16)]}
     */
    public static byte[] encrypt(String recipientPhone, byte[] plaintext) {
        SecretKeySpec key = deriveKey(recipientPhone);
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plaintext);

            ByteBuffer buf = ByteBuffer.allocate(IV_LENGTH + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return buf.array();

        } catch (Exception e) {
            Log.e(TAG, "Encryption failed for recipient: " + recipientPhone, e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Encrypts a plaintext string for a specific recipient.
     */
    public static byte[] encrypt(String recipientPhone, String plaintext) {
        return encrypt(recipientPhone, plaintext.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------
    //  Decryption
    // ---------------------------------------------------------------

    /**
     * Decrypts a payload that was encrypted with {@link #encrypt}.
     *
     * <p><strong>Critical:</strong> Pass the phone number whose key was used to
     * <em>encrypt</em>. Since the sender encrypts with the <strong>recipient's</strong>
     * phone number, the receiver must pass their <strong>own</strong> registered
     * phone number (the {@code recipientPhone} from the message).</p>
     *
     * @param phoneForDerivation the phone whose key was used to encrypt
     *                           (the <em>recipient</em> phone, i.e. the local user)
     * @param ciphertextWithIv   encrypted payload: {@code [IV (12)] [ciphertext] [GCM tag]}
     * @return decrypted plaintext bytes
     * @throws Exception if decryption fails (wrong key, tampered data, etc.)
     */
    public static byte[] decrypt(String phoneForDerivation, byte[] ciphertextWithIv) throws Exception {
        SecretKeySpec key = deriveKey(phoneForDerivation);
        ByteBuffer buf = ByteBuffer.wrap(ciphertextWithIv);

        byte[] iv = new byte[IV_LENGTH];
        buf.get(iv);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);

        return cipher.doFinal(ciphertext);
    }

    /**
     * Decrypts a payload and returns it as a UTF-8 string.
     *
     * @param phoneForDerivation the phone whose key was used to encrypt
     * @param ciphertextWithIv   encrypted payload
     * @return decrypted message text
     * @throws Exception if decryption fails
     */
    public static String decryptAsString(String phoneForDerivation, byte[] ciphertextWithIv)
            throws Exception {
        return new String(decrypt(phoneForDerivation, ciphertextWithIv), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------
    //  Nonce generation
    // ---------------------------------------------------------------

    /**
     * Generates a cryptographically secure random nonce for message uniqueness.
     */
    public static byte[] generateNonce() {
        byte[] nonce = new byte[NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    // ---------------------------------------------------------------
    //  Self-test / verification (runs on-device, prints to logcat)
    // ---------------------------------------------------------------

    /**
     * Runs an on-device encryption/decryption round-trip test with your
     * actual phone numbers and prints detailed results to logcat.
     *
     * <p>Call this from a debug menu, About dialog, or adb shell to verify
     * the app's crypto is working correctly on your device with your real
     * phone numbers.</p>
     *
     * <p><b>Tests performed:</b></p>
     * <ol>
     *   <li><b>Basic round-trip:</b> encrypt with {@code phoneB}, decrypt
     *       with {@code phoneB} → original text returned</li>
     *   <li><b>Format compatibility:</b> encrypt with {@code "+" + digits},
     *       decrypt with just {@code digits} (no '+') → same key derived</li>
     *   <li><b>Wrong-key rejection:</b> encrypt with {@code phoneB}, decrypt
     *       with {@code phoneA} → AES-GCM throws AEADBadTagException</li>
     *   <li><b>Persian / Unicode support:</b> encrypt/decrypt non-ASCII text
     *       → round-trips correctly via UTF-8</li>
     *   <li><b>Empty message:</b> encrypt/decrypt a zero-length message
     *       → one AES-GCM block is produced, decrypt returns 0 bytes</li>
     * </ol>
     *
     * @param phoneA the first phone number (e.g. the sender)
     * @param phoneB the second phone number (e.g. the recipient)
     * @return {@code true} if ALL tests pass
     */
    public static boolean runSelfTest(String phoneA, String phoneB) {
        Log.i(TAG, "═══════════════════════════════════════════════");
        Log.i(TAG, "  CRYPTO SELF-TEST STARTING");
        Log.i(TAG, "  Phone A (sender):     " + phoneA);
        Log.i(TAG, "  Phone B (recipient):  " + phoneB);
        Log.i(TAG, "═══════════════════════════════════════════════");

        boolean allPassed = true;

        // ── Test 1: Basic round-trip ─────────────────────────────
        String original = "Hello from " + phoneA + "! Test message: @#$%{[]}¥€";
        Log.i(TAG, "\n── [Test 1] Basic encrypt→decrypt round-trip ──");
        Log.i(TAG, "   Original text: " + original);
        try {
            byte[] ciphertext = encrypt(phoneB, original);
            Log.i(TAG, "   Encrypted size: " + ciphertext.length + " bytes");
            Log.i(TAG, "   (IV: first 12, ciphertext+tag: "
                    + (ciphertext.length - 12) + " bytes)");

            byte[] decrypted = decrypt(phoneB, ciphertext);
            String result = new String(decrypted, StandardCharsets.UTF_8);

            boolean pass = original.equals(result);
            Log.i(TAG, "   Decrypted text: " + result);
            Log.i(TAG, "   Matches original: " + (pass ? "✅ PASS" : "❌ FAIL"));
            if (!pass) allPassed = false;
        } catch (Exception e) {
            Log.e(TAG, "   ❌ Test 1 FAILED with exception", e);
            allPassed = false;
        }

        // ── Test 2: Key format compatibility ─────────────────────
        // encrypt with "+phoneB", decrypt with "phoneB" (no '+') → same key
        Log.i(TAG, "\n── [Test 2] Key format compatibility (+ vs no +) ──");
        String withPlus;
        String withoutPlus;
        if (phoneB.startsWith("+")) {
            withPlus = phoneB;
            withoutPlus = phoneB.substring(1);
        } else {
            withPlus = "+" + phoneB;
            withoutPlus = phoneB;
        }
        Log.i(TAG, "   Encrypt key context:  " + withPlus);
        Log.i(TAG, "   Decrypt key context:  " + withoutPlus);
        try {
            byte[] ct = encrypt(withPlus, "Format test");
            String decrypted = decryptAsString(withoutPlus, ct);
            boolean pass = "Format test".equals(decrypted);
            Log.i(TAG, "   Decrypted: " + decrypted);
            Log.i(TAG, "   Result: " + (pass ? "✅ PASS" : "❌ FAIL"));
            if (!pass) allPassed = false;
        } catch (Exception e) {
            Log.e(TAG, "   ❌ Test 2 FAILED with exception", e);
            allPassed = false;
        }

        // ── Test 3: Wrong-key rejection ──────────────────────────
        Log.i(TAG, "\n── [Test 3] Wrong-key rejection ──");
        Log.i(TAG, "   Encrypt with Phone B, decrypt with Phone A");
        Log.i(TAG, "   (should fail with AEADBadTagException or similar)");
        try {
            byte[] ct = encrypt(phoneB, "secret-message");
            try {
                decryptAsString(phoneA, ct);
                Log.e(TAG, "   ❌ Test 3 FAILED — decryption SUCCEEDED with wrong key!");
                allPassed = false;
            } catch (Exception e2) {
                String cn = e2.getClass().getSimpleName();
                Log.i(TAG, "   Decryption correctly rejected with: " + cn);
                Log.i(TAG, "   Message: " + e2.getMessage());
                Log.i(TAG, "   ✅ PASS (wrong key rejected)");
            }
        } catch (Exception e) {
            Log.e(TAG, "   ❌ Test 3 encrypt step failed", e);
            allPassed = false;
        }

        // ── Test 4: Unicode / non-ASCII text ─────────────────────
        Log.i(TAG, "\n── [Test 4] Unicode text support ──");
        String unicode = "مرحبا بالعالم • 你好世界 • नमस्ते दुनिया • Привет мир • ñoño";
        Log.i(TAG, "   Original: " + unicode);
        try {
            byte[] ct = encrypt(phoneB, unicode);
            String decrypted = decryptAsString(phoneB, ct);
            boolean pass = unicode.equals(decrypted);
            Log.i(TAG, "   Decrypted: " + decrypted);
            Log.i(TAG, "   Result: " + (pass ? "✅ PASS" : "❌ FAIL"));
            if (!pass) allPassed = false;
        } catch (Exception e) {
            Log.e(TAG, "   ❌ Test 4 FAILED with exception", e);
            allPassed = false;
        }

        // ── Test 5: Empty message ────────────────────────────────
        Log.i(TAG, "\n── [Test 5] Empty message ──");
        try {
            byte[] ct = encrypt(phoneB, new byte[0]);
            byte[] decrypted = decrypt(phoneB, ct);
            boolean pass = decrypted.length == 0;
            Log.i(TAG, "   Encrypted size: " + ct.length + " bytes");
            Log.i(TAG, "   Decrypted size: " + decrypted.length + " bytes");
            Log.i(TAG, "   Result: " + (pass ? "✅ PASS" : "❌ FAIL"));
            if (!pass) allPassed = false;
        } catch (Exception e) {
            Log.e(TAG, "   ❌ Test 5 FAILED with exception", e);
            allPassed = false;
        }

        // ── Summary ──────────────────────────────────────────────
        Log.i(TAG, "\n═══════════════════════════════════════════════");
        Log.i(TAG, allPassed
                ? "  ALL 5 TESTS PASSED ✅"
                : "  SOME TESTS FAILED ❌ — check above for details");
        Log.i(TAG, "═══════════════════════════════════════════════");
        return allPassed;
    }

    // ---------------------------------------------------------------
    //  Utilities
    // ---------------------------------------------------------------

    /**
     * Clears the key cache (e.g. on sign-out or identity reset).
     */
    public static void clearKeyCache() {
        keyCache.clear();
    }

    /**
     * Validates that a phone number matches E.164 format.
     * Must start with '+' followed by 1-15 digits.
     */
    public static boolean isValidE164(String phone) {
        return phone != null && phone.matches("^\\+[1-9]\\d{1,14}$");
    }
}
