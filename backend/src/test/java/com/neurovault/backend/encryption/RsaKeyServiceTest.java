package com.neurovault.backend.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RsaKeyService}.
 */
class RsaKeyServiceTest {

    private RsaKeyService rsaService;
    private AesEncryptionService aesService;

    @BeforeEach
    void setUp() {
        rsaService = new RsaKeyService();
        aesService = new AesEncryptionService();
    }

    @Test
    @DisplayName("generateKeyPair() should produce a valid RSA-4096 key pair")
    void testGenerateKeyPair() throws GeneralSecurityException {
        KeyPair keyPair = rsaService.generateKeyPair();

        assertNotNull(keyPair);
        assertNotNull(keyPair.getPublic());
        assertNotNull(keyPair.getPrivate());
        assertEquals("RSA", keyPair.getPublic().getAlgorithm());
        assertEquals("RSA", keyPair.getPrivate().getAlgorithm());
    }

    @Test
    @DisplayName("wrapKey/unwrapKey round-trip should recover original AES key")
    void testWrapUnwrap_RoundTrip() throws GeneralSecurityException {
        // Generate AES key
        SecretKey originalKey = aesService.generateKey();

        // Generate RSA key pair
        KeyPair rsaKeyPair = rsaService.generateKeyPair();

        // Wrap AES key
        String wrappedKey = rsaService.wrapKey(originalKey, rsaKeyPair.getPublic());
        assertNotNull(wrappedKey);
        assertFalse(wrappedKey.isEmpty());

        // Unwrap AES key
        SecretKey unwrappedKey = rsaService.unwrapKey(wrappedKey, rsaKeyPair.getPrivate());
        assertNotNull(unwrappedKey);
        assertEquals("AES", unwrappedKey.getAlgorithm());
        assertArrayEquals(originalKey.getEncoded(), unwrappedKey.getEncoded(),
                "Unwrapped key should match the original AES key");
    }

    @Test
    @DisplayName("unwrapping with wrong private key should fail")
    void testUnwrap_WrongPrivateKey_Fails() throws GeneralSecurityException {
        SecretKey aesKey = aesService.generateKey();

        KeyPair correctPair = rsaService.generateKeyPair();
        KeyPair wrongPair = rsaService.generateKeyPair();

        // Wrap with correct public key
        String wrappedKey = rsaService.wrapKey(aesKey, correctPair.getPublic());

        // Attempt to unwrap with wrong private key
        assertThrows(GeneralSecurityException.class, () ->
                rsaService.unwrapKey(wrappedKey, wrongPair.getPrivate()),
                "Unwrapping with wrong private key should throw GeneralSecurityException");
    }

    @Test
    @DisplayName("encodePublicKey/encodePrivateKey should produce non-empty Base64 strings")
    void testEncodeKeys() throws GeneralSecurityException {
        KeyPair keyPair = rsaService.generateKeyPair();

        String publicKeyB64 = rsaService.encodePublicKey(keyPair.getPublic());
        String privateKeyB64 = rsaService.encodePrivateKey(keyPair.getPrivate());

        assertNotNull(publicKeyB64);
        assertNotNull(privateKeyB64);
        assertFalse(publicKeyB64.isEmpty());
        assertFalse(privateKeyB64.isEmpty());
        assertTrue(publicKeyB64.length() > 100, "RSA-4096 public key should be substantial");
        assertTrue(privateKeyB64.length() > 100, "RSA-4096 private key should be substantial");
    }

    @Test
    @DisplayName("wrapping same key twice should produce different wrapped values")
    void testWrapKey_ProducesDifferentOutputs() throws GeneralSecurityException {
        SecretKey aesKey = aesService.generateKey();
        KeyPair rsaKeyPair = rsaService.generateKeyPair();

        String wrapped1 = rsaService.wrapKey(aesKey, rsaKeyPair.getPublic());
        String wrapped2 = rsaService.wrapKey(aesKey, rsaKeyPair.getPublic());

        // OAEP padding includes randomness, so wrappings should differ
        assertNotEquals(wrapped1, wrapped2,
                "OAEP wrapping should produce different outputs due to random padding");
    }
}
