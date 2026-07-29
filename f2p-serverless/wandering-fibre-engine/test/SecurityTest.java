package com.antor.f2p.engine.test;

import com.antor.f2p.engine.core.FibreSecurityHandler;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Validates AES-256-GCM encryption/decryption round-trips and the ECDH
 * handshake protocol.
 *
 * <p>Run from project root:
 * <pre>{@code
 * javac -d out $(find f2p-serverless/wandering-fibre-engine -name '*.java')
 * java -cp out com.antor.f2p.engine.test.SecurityTest
 * }</pre>
 * </p>
 */
public class SecurityTest {

    private int passed;
    private int failed;

    public static void main(String[] args) {
        SecurityTest test = new SecurityTest();
        test.run();
    }

    void run() {
        System.out.println("[SECURITY] ========================================");
        System.out.println("[SECURITY] Encryption & Handshake Test");
        System.out.println("[SECURITY] ========================================\n");

        testEncryptDecryptRoundTrip();
        testHandshakeAndDeriveKey();
        testDecryptWithWrongKeyFails();
        testEmptyPayload();
        testMultiplePeers();

        System.out.println("\n[SECURITY] ========================================");
        System.out.println("[SECURITY] " + (passed + failed) + " total | "
                + passed + " passed | " + failed + " failed");
        System.out.println("[SECURITY] ========================================");
        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * Complete a two-way ECDH handshake between two handlers.
     *
     * @param a           first handler
     * @param aNameForB   what handler 'a' should name its session for 'b'
     * @param b           second handler
     * @param bNameForA   what handler 'b' should name its session for 'a'
     */
    private static void handshake(FibreSecurityHandler a, String aNameForB,
                                  FibreSecurityHandler b, String bNameForA) {
        byte[] aPubForB = a.performHandshake(aNameForB, b.getLocalPublicKey());
        byte[] bPubForA = b.performHandshake(bNameForA, a.getLocalPublicKey());
    }

    // ---------------------------------------------------------------
    //  1. Basic encrypt → decrypt round-trip
    // ---------------------------------------------------------------

    void testEncryptDecryptRoundTrip() {
        System.out.print("[TEST] Encrypt/decrypt round-trip... ");
        try {
            FibreSecurityHandler alice = new FibreSecurityHandler();
            FibreSecurityHandler bob = new FibreSecurityHandler();
            alice.initialize();
            bob.initialize();

            handshake(alice, "bob", bob, "alice");

            String plaintext = "Hello from Alice — this is a secure mesh test!";
            byte[] ciphertext = alice.encrypt("bob", plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] decrypted = bob.decrypt("alice", ciphertext);
            String result = new String(decrypted, StandardCharsets.UTF_8);

            if (plaintext.equals(result)) {
                System.out.println("PASS (" + ciphertext.length + " bytes encrypted)");
                passed++;
            } else {
                System.out.println("FAIL — decrypted text mismatch");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL — exception: " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  2. Handshake produces matching session keys
    // ---------------------------------------------------------------

    void testHandshakeAndDeriveKey() {
        System.out.print("[TEST] Handshake key derivation... ");
        try {
            FibreSecurityHandler alice = new FibreSecurityHandler();
            FibreSecurityHandler bob = new FibreSecurityHandler();
            alice.initialize();
            bob.initialize();

            handshake(alice, "bob", bob, "alice");

            if (alice.hasSessionWith("bob") && bob.hasSessionWith("alice")) {
                System.out.println("PASS (both sides have session keys)");
                passed++;
            } else {
                System.out.println("FAIL — session keys not established");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL — exception: " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  3. Decryption with wrong key fails
    // ---------------------------------------------------------------

    void testDecryptWithWrongKeyFails() {
        System.out.print("[TEST] Wrong key rejects decryption... ");
        try {
            FibreSecurityHandler alice = new FibreSecurityHandler();
            FibreSecurityHandler bob = new FibreSecurityHandler();
            FibreSecurityHandler eve = new FibreSecurityHandler(); // eavesdropper
            alice.initialize();
            bob.initialize();
            eve.initialize();

            handshake(alice, "bob", bob, "alice");
            // Eve also tries to handshake with Alice
            handshake(eve, "alice", alice, "eve");

            // Alice encrypts for Bob
            byte[] ciphertext = alice.encrypt("bob", "secret".getBytes(StandardCharsets.UTF_8));

            // Eve tries to decrypt (should fail — wrong session key)
            try {
                eve.decrypt("alice", ciphertext);
                System.out.println("FAIL — Eve decrypted with wrong key!");
                failed++;
            } catch (RuntimeException e) {
                Throwable cause = e.getCause();
                if (cause instanceof javax.crypto.AEADBadTagException
                        || cause instanceof GeneralSecurityException) {
                    System.out.println("PASS (Eve rejected: " + cause.getClass().getSimpleName() + ")");
                } else {
                    System.out.println("PASS (Eve rejected: " + e.getClass().getSimpleName() + ")");
                }
                passed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL — exception: " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  4. Empty payload
    // ---------------------------------------------------------------

    void testEmptyPayload() {
        System.out.print("[TEST] Empty payload encryption... ");
        try {
            FibreSecurityHandler alice = new FibreSecurityHandler();
            FibreSecurityHandler bob = new FibreSecurityHandler();
            alice.initialize();
            bob.initialize();

            handshake(alice, "bob", bob, "alice");

            byte[] ciphertext = alice.encrypt("bob", new byte[0]);
            byte[] decrypted = bob.decrypt("alice", ciphertext);

            if (decrypted.length == 0) {
                System.out.println("PASS");
                passed++;
            } else {
                System.out.println("FAIL — expected 0 bytes, got " + decrypted.length);
                failed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL — exception: " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  5. Multiple peer sessions
    // ---------------------------------------------------------------

    void testMultiplePeers() {
        System.out.print("[TEST] Multiple peer sessions... ");
        try {
            FibreSecurityHandler hub = new FibreSecurityHandler();
            FibreSecurityHandler peer1 = new FibreSecurityHandler();
            FibreSecurityHandler peer2 = new FibreSecurityHandler();
            hub.initialize();
            peer1.initialize();
            peer2.initialize();

            // Complete two-way handshakes with both peers
            handshake(hub, "peer1", peer1, "hub");
            handshake(hub, "peer2", peer2, "hub");

            if (hub.getActiveSessionCount() != 2) {
                System.out.println("FAIL — expected 2 hub sessions, got " + hub.getActiveSessionCount());
                failed++;
                return;
            }
            if (peer1.getActiveSessionCount() != 1 || peer2.getActiveSessionCount() != 1) {
                System.out.println("FAIL — peers expected 1 session each, got "
                        + peer1.getActiveSessionCount() + "/" + peer2.getActiveSessionCount());
                failed++;
                return;
            }

            // Hub encrypts for each peer separately
            byte[] ct1 = hub.encrypt("peer1", "msg1".getBytes(StandardCharsets.UTF_8));
            byte[] ct2 = hub.encrypt("peer2", "msg2".getBytes(StandardCharsets.UTF_8));

            // Each peer decrypts its own message
            String m1 = new String(peer1.decrypt("hub", ct1), StandardCharsets.UTF_8);
            String m2 = new String(peer2.decrypt("hub", ct2), StandardCharsets.UTF_8);

            if ("msg1".equals(m1) && "msg2".equals(m2)) {
                System.out.println("PASS (2 sessions, per-peer encrypt/decrypt OK)");
                passed++;
            } else {
                System.out.println("FAIL — payload mismatch: '" + m1 + "' / '" + m2 + "'");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL — exception: " + e.getMessage());
            failed++;
        }
    }
}
