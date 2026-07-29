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
     * @param phoneNumber the phone number in E.164 format
     * @return AES secret key specification
     */
    public static SecretKeySpec deriveKey(String phoneNumber) {
        return keyCache.computeIfAbsent(phoneNumber, phone -> {
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
     * @param senderPhone    the sender's phone number (used as key context)
     * @param ciphertextWithIv encrypted payload: {@code [IV (12)] [ciphertext] [GCM tag]}
     * @return decrypted plaintext bytes
     * @throws Exception if decryption fails (wrong key, tampered data, etc.)
     */
    public static byte[] decrypt(String senderPhone, byte[] ciphertextWithIv) throws Exception {
        SecretKeySpec key = deriveKey(senderPhone);
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
     * @param senderPhone    the sender's phone number
     * @param ciphertextWithIv encrypted payload
     * @return decrypted message text
     * @throws Exception if decryption fails
     */
    public static String decryptAsString(String senderPhone, byte[] ciphertextWithIv)
            throws Exception {
        return new String(decrypt(senderPhone, ciphertextWithIv), StandardCharsets.UTF_8);
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
