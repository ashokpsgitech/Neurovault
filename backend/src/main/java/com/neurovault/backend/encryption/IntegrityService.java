package com.neurovault.backend.encryption;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/**
 * Service for data integrity verification using SHA-256 hashing and CRC32 checksums.
 *
 * <p>SHA-256 provides cryptographic-strength integrity checking for files and chunks.
 * CRC32 provides fast, lightweight integrity checks suitable for per-chunk verification
 * during transfers.</p>
 */
@Service
@Slf4j
public class IntegrityService {

    private static final String SHA_256 = "SHA-256";
    private static final int BUFFER_SIZE = 8192;

    /**
     * Computes the SHA-256 hash of data from an input stream.
     *
     * @param data the input stream to hash
     * @return lowercase hex string of the SHA-256 digest
     * @throws IOException if an I/O error occurs
     */
    public String computeSha256(InputStream data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = data.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all Java implementations
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Computes the SHA-256 hash of a byte array.
     *
     * @param data the byte array to hash
     * @return lowercase hex string of the SHA-256 digest
     */
    public String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return bytesToHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Computes a CRC32 checksum of a byte array.
     *
     * @param data the byte array to checksum
     * @return the checksum as a decimal string
     */
    public String computeChecksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return Long.toString(crc.getValue());
    }

    /**
     * Verifies that the SHA-256 hash of the data matches the expected hash.
     *
     * @param data         the input stream to verify
     * @param expectedHash the expected lowercase hex SHA-256 hash
     * @return {@code true} if the computed hash matches the expected hash
     * @throws IOException if an I/O error occurs
     */
    public boolean verifySha256(InputStream data, String expectedHash) throws IOException {
        String actualHash = computeSha256(data);
        boolean match = actualHash.equalsIgnoreCase(expectedHash);
        if (!match) {
            log.warn("SHA-256 verification failed: expected={}, actual={}", expectedHash, actualHash);
        }
        return match;
    }

    /**
     * Verifies that the SHA-256 hash of the byte array matches the expected hash.
     *
     * @param data         the byte array to verify
     * @param expectedHash the expected lowercase hex SHA-256 hash
     * @return {@code true} if the computed hash matches the expected hash
     */
    public boolean verifySha256(byte[] data, String expectedHash) {
        String actualHash = computeSha256(data);
        boolean match = actualHash.equalsIgnoreCase(expectedHash);
        if (!match) {
            log.warn("SHA-256 verification failed: expected={}, actual={}", expectedHash, actualHash);
        }
        return match;
    }

    /**
     * Verifies that the CRC32 checksum of the data matches the expected checksum.
     *
     * @param data             the byte array to verify
     * @param expectedChecksum the expected checksum as a decimal string
     * @return {@code true} if the computed checksum matches the expected checksum
     */
    public boolean verifyChecksum(byte[] data, String expectedChecksum) {
        String actualChecksum = computeChecksum(data);
        boolean match = actualChecksum.equals(expectedChecksum);
        if (!match) {
            log.warn("Checksum verification failed: expected={}, actual={}", expectedChecksum, actualChecksum);
        }
        return match;
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
