package com.neurovault.backend.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AesEncryptionService}.
 */
class AesEncryptionServiceTest {

    private AesEncryptionService aesService;

    @BeforeEach
    void setUp() {
        aesService = new AesEncryptionService();
    }

    @Test
    @DisplayName("generateKey() should produce a valid AES-256 key")
    void testGenerateKey() throws GeneralSecurityException {
        SecretKey key = aesService.generateKey();

        assertNotNull(key);
        assertEquals("AES", key.getAlgorithm());
        assertEquals(32, key.getEncoded().length, "AES-256 key should be 32 bytes");
    }

    @Test
    @DisplayName("encrypt/decrypt round-trip for small file should recover original data")
    void testEncryptDecrypt_SmallFile() throws Exception {
        SecretKey key = aesService.generateKey();
        byte[] original = "Hello, NeuroVault! This is a test file.".getBytes();

        // Encrypt
        ByteArrayOutputStream cipherStream = new ByteArrayOutputStream();
        EncryptionResult result = aesService.encrypt(
                new ByteArrayInputStream(original), cipherStream, key);

        byte[] ciphertext = cipherStream.toByteArray();
        assertNotNull(result);
        assertNotNull(result.iv());
        assertEquals(12, result.iv().length, "GCM IV should be 12 bytes");
        assertEquals(original.length, result.plaintextSize());
        assertNotNull(result.plaintextHash());
        assertFalse(result.plaintextHash().isEmpty());
        assertFalse(Arrays.equals(original, ciphertext), "Ciphertext should differ from plaintext");

        // Decrypt
        ByteArrayOutputStream plainStream = new ByteArrayOutputStream();
        aesService.decrypt(new ByteArrayInputStream(ciphertext), plainStream, key);

        assertArrayEquals(original, plainStream.toByteArray(),
                "Decrypted data should match original");
    }

    @Test
    @DisplayName("encrypt/decrypt round-trip for large file (>4MB) should recover original data")
    void testEncryptDecrypt_LargeFile() throws Exception {
        SecretKey key = aesService.generateKey();

        // Generate 5 MB of random data
        byte[] original = new byte[5 * 1024 * 1024];
        new Random(42).nextBytes(original);

        // Encrypt
        ByteArrayOutputStream cipherStream = new ByteArrayOutputStream();
        aesService.encrypt(new ByteArrayInputStream(original), cipherStream, key);

        // Decrypt
        ByteArrayOutputStream plainStream = new ByteArrayOutputStream();
        aesService.decrypt(new ByteArrayInputStream(cipherStream.toByteArray()), plainStream, key);

        assertArrayEquals(original, plainStream.toByteArray(),
                "Decrypted data should match original for large files");
    }

    @Test
    @DisplayName("encrypting same plaintext twice should produce different ciphertexts (random IV)")
    void testEncrypt_ProducesDifferentCiphertext() throws Exception {
        SecretKey key = aesService.generateKey();
        byte[] original = "Same data encrypted twice".getBytes();

        ByteArrayOutputStream cipher1 = new ByteArrayOutputStream();
        aesService.encrypt(new ByteArrayInputStream(original), cipher1, key);

        ByteArrayOutputStream cipher2 = new ByteArrayOutputStream();
        aesService.encrypt(new ByteArrayInputStream(original), cipher2, key);

        assertFalse(Arrays.equals(cipher1.toByteArray(), cipher2.toByteArray()),
                "Two encryptions of same data should produce different ciphertext due to random IV");
    }

    @Test
    @DisplayName("decrypting with wrong key should throw exception")
    void testDecrypt_WrongKey_Fails() throws Exception {
        SecretKey encryptKey = aesService.generateKey();
        SecretKey wrongKey = aesService.generateKey();
        byte[] original = "Secret data".getBytes();

        ByteArrayOutputStream cipherStream = new ByteArrayOutputStream();
        aesService.encrypt(new ByteArrayInputStream(original), cipherStream, encryptKey);

        ByteArrayOutputStream plainStream = new ByteArrayOutputStream();
        assertThrows(AEADBadTagException.class, () ->
                aesService.decrypt(new ByteArrayInputStream(cipherStream.toByteArray()),
                        plainStream, wrongKey),
                "Decryption with wrong key should fail with AEADBadTagException");
    }

    @Test
    @DisplayName("encryptBytes/decryptBytes convenience methods should work")
    void testEncryptDecryptBytes() throws Exception {
        SecretKey key = aesService.generateKey();
        byte[] original = "Convenience method test".getBytes();

        byte[] encrypted = aesService.encryptBytes(original, key);
        byte[] decrypted = aesService.decryptBytes(encrypted, key);

        assertArrayEquals(original, decrypted);
    }

    @Test
    @DisplayName("encrypting empty data should work correctly")
    void testEncryptDecrypt_EmptyData() throws Exception {
        SecretKey key = aesService.generateKey();
        byte[] original = new byte[0];

        byte[] encrypted = aesService.encryptBytes(original, key);
        assertNotNull(encrypted);
        assertTrue(encrypted.length > 0, "Even empty plaintext produces ciphertext (IV + auth tag)");

        byte[] decrypted = aesService.decryptBytes(encrypted, key);
        assertArrayEquals(original, decrypted);
    }
}
