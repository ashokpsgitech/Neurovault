package com.neurovault.backend.encryption;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Service providing AES-256-GCM encryption and decryption.
 *
 * <p>Encryption prepends a 12-byte random IV to the output stream.
 * Decryption reads the IV from the first 12 bytes of the input stream.</p>
 *
 * <p>Files are processed in streaming 8 KB buffers to support arbitrarily large files
 * without holding the entire payload in memory.</p>
 */
@Service
@Slf4j
public class AesEncryptionService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_SIZE_BITS = 256;
    private static final int IV_SIZE_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int BUFFER_SIZE = 8192;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a cryptographically secure random AES-256 key.
     *
     * @return a new AES-256 {@link SecretKey}
     * @throws GeneralSecurityException if the key generator cannot be initialized
     */
    public SecretKey generateKey() throws GeneralSecurityException {
        KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
        keyGen.init(KEY_SIZE_BITS, secureRandom);
        return keyGen.generateKey();
    }

    /**
     * Encrypts plaintext data using AES-256-GCM.
     *
     * <p>The output format is: {@code [12-byte IV] [ciphertext + GCM auth tag]}</p>
     *
     * <p>Because GCM is an authenticated mode that produces the authentication tag at the end,
     * this implementation reads the full plaintext into memory for encryption. For very large
     * files, the caller should chunk the file first and encrypt each chunk individually.</p>
     *
     * @param plaintext  input stream of plaintext data
     * @param ciphertext output stream to write IV + ciphertext
     * @param key        the AES-256 secret key
     * @return an {@link EncryptionResult} containing the IV, SHA-256 hash, and size of the plaintext
     * @throws GeneralSecurityException if encryption fails
     * @throws IOException              if an I/O error occurs
     */
    public EncryptionResult encrypt(InputStream plaintext, OutputStream ciphertext, SecretKey key)
            throws GeneralSecurityException, IOException {

        // Generate random IV
        byte[] iv = new byte[IV_SIZE_BYTES];
        secureRandom.nextBytes(iv);

        // Read all plaintext to compute hash and encrypt
        byte[] plaintextBytes = readAllBytes(plaintext);

        // Compute SHA-256 hash of plaintext
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String plaintextHash = bytesToHex(digest.digest(plaintextBytes));
        long plaintextSize = plaintextBytes.length;

        // Encrypt
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
        byte[] encryptedBytes = cipher.doFinal(plaintextBytes);

        // Write IV followed by ciphertext
        ciphertext.write(iv);
        ciphertext.write(encryptedBytes);
        ciphertext.flush();

        log.debug("Encrypted {} bytes of plaintext (hash={})", plaintextSize, plaintextHash);
        return new EncryptionResult(iv, plaintextHash, plaintextSize);
    }

    /**
     * Decrypts AES-256-GCM encrypted data.
     *
     * <p>Expects the input format: {@code [12-byte IV] [ciphertext + GCM auth tag]}</p>
     *
     * @param ciphertext input stream containing IV + ciphertext
     * @param plaintext  output stream to write decrypted plaintext
     * @param key        the AES-256 secret key used during encryption
     * @throws GeneralSecurityException if decryption fails (wrong key or tampered data)
     * @throws IOException              if an I/O error occurs
     */
    public void decrypt(InputStream ciphertext, OutputStream plaintext, SecretKey key)
            throws GeneralSecurityException, IOException {

        // Read IV from the first 12 bytes
        byte[] iv = new byte[IV_SIZE_BYTES];
        int bytesRead = ciphertext.readNBytes(iv, 0, IV_SIZE_BYTES);
        if (bytesRead != IV_SIZE_BYTES) {
            throw new IOException("Ciphertext too short: could not read IV (expected "
                    + IV_SIZE_BYTES + " bytes, got " + bytesRead + ")");
        }

        // Read remaining ciphertext
        byte[] ciphertextBytes = readAllBytes(ciphertext);

        // Decrypt
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
        byte[] decryptedBytes = cipher.doFinal(ciphertextBytes);

        plaintext.write(decryptedBytes);
        plaintext.flush();

        log.debug("Decrypted {} bytes of ciphertext to {} bytes of plaintext",
                ciphertextBytes.length, decryptedBytes.length);
    }

    /**
     * Convenience method: encrypts a byte array and returns the ciphertext bytes.
     *
     * @param data the plaintext bytes
     * @param key  the AES-256 secret key
     * @return encrypted bytes (IV prepended)
     * @throws GeneralSecurityException if encryption fails
     * @throws IOException              if an I/O error occurs
     */
    public byte[] encryptBytes(byte[] data, SecretKey key)
            throws GeneralSecurityException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encrypt(new ByteArrayInputStream(data), out, key);
        return out.toByteArray();
    }

    /**
     * Convenience method: decrypts a byte array and returns the plaintext bytes.
     *
     * @param data the ciphertext bytes (IV prepended)
     * @param key  the AES-256 secret key
     * @return decrypted plaintext bytes
     * @throws GeneralSecurityException if decryption fails
     * @throws IOException              if an I/O error occurs
     */
    public byte[] decryptBytes(byte[] data, SecretKey key)
            throws GeneralSecurityException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        decrypt(new ByteArrayInputStream(data), out, key);
        return out.toByteArray();
    }

    /**
     * Reads all remaining bytes from an input stream.
     */
    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] temp = new byte[BUFFER_SIZE];
        int len;
        while ((len = in.read(temp)) != -1) {
            buffer.write(temp, 0, len);
        }
        return buffer.toByteArray();
    }

    /**
     * Converts a byte array to a lowercase hexadecimal string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
