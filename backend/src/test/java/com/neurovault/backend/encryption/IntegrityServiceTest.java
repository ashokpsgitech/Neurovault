package com.neurovault.backend.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IntegrityService}.
 */
class IntegrityServiceTest {

    private IntegrityService integrityService;

    @BeforeEach
    void setUp() {
        integrityService = new IntegrityService();
    }

    @Test
    @DisplayName("computeSha256 should produce consistent hash for same input")
    void testComputeSha256_Consistent() throws IOException {
        byte[] data = "Hello, World!".getBytes();

        String hash1 = integrityService.computeSha256(new ByteArrayInputStream(data));
        String hash2 = integrityService.computeSha256(data);

        assertNotNull(hash1);
        assertEquals(64, hash1.length(), "SHA-256 hex should be 64 characters");
        assertEquals(hash1, hash2, "Stream and byte array hashing should produce same result");
    }

    @Test
    @DisplayName("computeSha256 should produce different hashes for different inputs")
    void testComputeSha256_DifferentInput() {
        byte[] data1 = "Hello".getBytes();
        byte[] data2 = "World".getBytes();

        String hash1 = integrityService.computeSha256(data1);
        String hash2 = integrityService.computeSha256(data2);

        assertNotEquals(hash1, hash2, "Different inputs should produce different hashes");
    }

    @Test
    @DisplayName("computeChecksum should produce valid CRC32 checksum")
    void testComputeChecksum() {
        byte[] data = "Test data for checksum".getBytes();

        String checksum = integrityService.computeChecksum(data);
        assertNotNull(checksum);
        assertFalse(checksum.isEmpty());

        // CRC32 is deterministic
        String checksum2 = integrityService.computeChecksum(data);
        assertEquals(checksum, checksum2, "Same data should produce same checksum");
    }

    @Test
    @DisplayName("computeChecksum should produce different values for different inputs")
    void testComputeChecksum_DifferentInput() {
        byte[] data1 = "Data A".getBytes();
        byte[] data2 = "Data B".getBytes();

        String checksum1 = integrityService.computeChecksum(data1);
        String checksum2 = integrityService.computeChecksum(data2);

        assertNotEquals(checksum1, checksum2,
                "Different data should produce different checksums");
    }

    @Test
    @DisplayName("verifySha256 should return true for matching hash")
    void testVerifySha256_Valid() throws IOException {
        byte[] data = "Verified data".getBytes();
        String correctHash = integrityService.computeSha256(data);

        assertTrue(integrityService.verifySha256(new ByteArrayInputStream(data), correctHash));
        assertTrue(integrityService.verifySha256(data, correctHash));
    }

    @Test
    @DisplayName("verifySha256 should return false for corrupted data")
    void testVerifySha256_Invalid() throws IOException {
        byte[] original = "Original data".getBytes();
        String originalHash = integrityService.computeSha256(original);

        byte[] corrupted = "Corrupted data".getBytes();
        assertFalse(integrityService.verifySha256(corrupted, originalHash));
        assertFalse(integrityService.verifySha256(new ByteArrayInputStream(corrupted), originalHash));
    }

    @Test
    @DisplayName("verifyChecksum should return true for matching checksum")
    void testVerifyChecksum_Valid() {
        byte[] data = "Checksum test".getBytes();
        String correctChecksum = integrityService.computeChecksum(data);

        assertTrue(integrityService.verifyChecksum(data, correctChecksum));
    }

    @Test
    @DisplayName("verifyChecksum should return false for corrupted data")
    void testVerifyChecksum_Invalid() {
        byte[] original = "Original".getBytes();
        String originalChecksum = integrityService.computeChecksum(original);

        byte[] corrupted = "Modified".getBytes();
        assertFalse(integrityService.verifyChecksum(corrupted, originalChecksum));
    }

    @Test
    @DisplayName("computeSha256 on empty data should produce known empty-hash")
    void testComputeSha256_EmptyData() {
        byte[] empty = new byte[0];
        String hash = integrityService.computeSha256(empty);
        assertNotNull(hash);
        assertEquals(64, hash.length());
        // Known SHA-256 of empty input
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }
}
