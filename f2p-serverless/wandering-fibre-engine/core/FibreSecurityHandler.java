package com.antor.f2p.engine.core;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Lightweight security handler providing end-to-end payload encryption for
 * all {@link com.antor.f2p.engine.api.FibrePacket} raw data buffers.
 * <p>
 * <strong>Cipher:</strong> AES-256-GCM (no padding, 128-bit tag, 96-bit IV).
 * <br>
 * <strong>Key agreement:</strong> ECDH (secp256r1 / P-256) with ephemeral
 * key pairs — peers exchange public keys during a handshake and derive a
 * shared AES-256 session key.
 * </p>
 *
 * <p>
 * <strong>Packet format (encrypted payload):</strong>
 * {@code [IV (12 bytes)] [ciphertext (variable)] [GCM tag (16 bytes)]}
 * </p>
 */
public class FibreSecurityHandler {

    private static final Logger LOG = Logger.getLogger(FibreSecurityHandler.class.getName());

    private static final String KEY_AGREEMENT_ALGO = "ECDH";
    private static final String KEYGEN_ALGO = "EC";
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;  // bits
    private static final int IV_LENGTH = 12;        // bytes
    private static final int AES_KEY_LENGTH = 32;   // 256 bits

    /** This node's EC key pair (persistent for the session). */
    private KeyPair localKeyPair;

    /** Peer ID → derived AES-256 session key (established during handshake). */
    private final ConcurrentHashMap<String, SecretKey> sessionKeys;

    /** Peer ID → their EC public key (cached after handshake). */
    private final ConcurrentHashMap<String, PublicKey> peerPublicKeys;

    /** Flag indicating whether the local key pair has been initialised. */
    private volatile boolean initialized;

    public FibreSecurityHandler() {
        this.sessionKeys = new ConcurrentHashMap<>();
        this.peerPublicKeys = new ConcurrentHashMap<>();
    }

    /**
     * Initialises the crypto subsystem by generating the local EC key pair.
     */
    public void initialize() {
        if (initialized) return;
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEYGEN_ALGO);
            kpg.initialize(256, new SecureRandom());
            this.localKeyPair = kpg.generateKeyPair();
            this.initialized = true;
            LOG.info("FibreSecurityHandler initialised (EC P-256)");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise crypto", e);
        }
    }

    // ---------------------------------------------------------------
    //  Handshake
    // ---------------------------------------------------------------

    /**
     * Performs an ECDH handshake with a peer.
     * <p>
     * The caller provides the peer's public key (received out-of-band or via
     * the discovery protocol). The handler computes the shared secret and
     * derives an AES-256 session key.
     * </p>
     *
     * @param peerId       the remote peer's identifier
     * @param peerKeyBytes the remote peer's encoded EC public key (X.509)
     * @return this node's encoded EC public key for the peer to complete the handshake
     */
    public byte[] performHandshake(String peerId, byte[] peerKeyBytes) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(peerKeyBytes, "peerKeyBytes");
        if (!initialized) {
            throw new IllegalStateException("FibreSecurityHandler not initialised");
        }

        try {
            // Decode peer's public key
            KeyFactory kf = KeyFactory.getInstance(KEYGEN_ALGO);
            PublicKey peerPublic = kf.generatePublic(new X509EncodedKeySpec(peerKeyBytes));
            peerPublicKeys.put(peerId, peerPublic);

            // ECDH key agreement
            KeyAgreement ka = KeyAgreement.getInstance(KEY_AGREEMENT_ALGO);
            ka.init(localKeyPair.getPrivate());
            ka.doPhase(peerPublic, true);
            byte[] sharedSecret = ka.generateSecret();

            // Derive AES-256 key (SHA-256 hash of the shared secret)
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] aesKeyBytes = md.digest(sharedSecret);
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            sessionKeys.put(peerId, aesKey);

            LOG.fine("Handshake completed for peer: " + peerId);
            return localKeyPair.getPublic().getEncoded();

        } catch (Exception e) {
            throw new RuntimeException("Handshake failed for peer: " + peerId, e);
        }
    }

    /** Returns {@code true} if a session key has been established with the peer. */
    public boolean hasSessionWith(String peerId) {
        return sessionKeys.containsKey(peerId);
    }

    /** Removes the session key for a peer (e.g. on disconnect). */
    public void removeSession(String peerId) {
        sessionKeys.remove(peerId);
        peerPublicKeys.remove(peerId);
    }

    // ---------------------------------------------------------------
    //  Encryption / Decryption
    // ---------------------------------------------------------------

    /**
     * Encrypts the raw payload of a packet destined for the given peer.
     *
     * @param peerId    the intended recipient
     * @param plaintext the plaintext data buffer
     * @return encrypted payload: {@code [IV (12)] [ciphertext] [GCM tag (16)]}
     */
    public byte[] encrypt(String peerId, byte[] plaintext) {
        SecretKey key = sessionKeys.get(peerId);
        if (key == null) {
            throw new IllegalStateException("No session key for peer: " + peerId);
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plaintext);

            ByteBuffer buf = ByteBuffer.allocate(IV_LENGTH + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return buf.array();

        } catch (Exception e) {
            throw new RuntimeException("Encryption failed for peer: " + peerId, e);
        }
    }

    /**
     * Decrypts a payload that was encrypted with {@link #encrypt}.
     *
     * @param peerId          the source peer that encrypted this data
     * @param ciphertextWithIv encrypted payload: {@code [IV (12)] [ciphertext] [GCM tag]}
     * @return decrypted plaintext
     */
    public byte[] decrypt(String peerId, byte[] ciphertextWithIv) {
        SecretKey key = sessionKeys.get(peerId);
        if (key == null) {
            throw new IllegalStateException("No session key for peer: " + peerId);
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(ciphertextWithIv);
            byte[] iv = new byte[IV_LENGTH];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            return cipher.doFinal(ciphertext);

        } catch (Exception e) {
            throw new RuntimeException("Decryption failed for peer: " + peerId, e);
        }
    }

    // ---------------------------------------------------------------
    //  Accessors
    // ---------------------------------------------------------------

    /** Returns this node's encoded EC public key (X.509) for distribution. */
    public byte[] getLocalPublicKey() {
        if (localKeyPair == null) return new byte[0];
        return localKeyPair.getPublic().getEncoded();
    }

    /** Returns the number of active session keys. */
    public int getActiveSessionCount() {
        return sessionKeys.size();
    }

    /** Clears all session keys (e.g. on full shutdown). */
    public void clearAllSessions() {
        sessionKeys.clear();
        peerPublicKeys.clear();
    }
}
