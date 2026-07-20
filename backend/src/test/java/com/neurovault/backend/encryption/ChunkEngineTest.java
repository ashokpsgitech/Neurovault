package com.neurovault.backend.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChunkEngine}.
 */
class ChunkEngineTest {

    private IntegrityService integrityService;
    private ChunkEngine chunkEngine;

    /** Use a small chunk size (1 KB) for faster testing. */
    private static final int TEST_CHUNK_SIZE = 1024;

    @BeforeEach
    void setUp() {
        integrityService = new IntegrityService();
        chunkEngine = new ChunkEngine(integrityService, TEST_CHUNK_SIZE);
    }

    @Test
    @DisplayName("splitFile should produce a single chunk for data smaller than chunk size")
    void testSplitFile_SingleChunk() throws IOException {
        byte[] data = "Small file content".getBytes();
        UUID ownerId = UUID.randomUUID();

        List<ChunkData> chunks = chunkEngine.splitFile(
                new ByteArrayInputStream(data), data.length, ownerId);

        assertEquals(1, chunks.size());

        ChunkData chunk = chunks.get(0);
        assertEquals(0, chunk.getChunkIndex());
        assertEquals(data.length, chunk.getChunkSize());
        assertNotNull(chunk.getChunkId());
        assertNotNull(chunk.getSha256Hash());
        assertNotNull(chunk.getChecksum());
        assertEquals(ownerId, chunk.getOwnerId());
        assertEquals(ChunkStatus.PENDING, chunk.getStatus());
        assertArrayEquals(data, chunk.getData());
    }

    @Test
    @DisplayName("splitFile should produce correct number of chunks for multi-chunk data")
    void testSplitFile_MultipleChunks() throws IOException {
        // 3 * 1024 bytes = exactly 3 chunks with test chunk size
        byte[] data = new byte[3 * TEST_CHUNK_SIZE];
        new Random(42).nextBytes(data);
        UUID ownerId = UUID.randomUUID();

        List<ChunkData> chunks = chunkEngine.splitFile(
                new ByteArrayInputStream(data), data.length, ownerId);

        assertEquals(3, chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getChunkIndex());
            assertEquals(TEST_CHUNK_SIZE, chunks.get(i).getChunkSize());
        }
    }

    @Test
    @DisplayName("splitFile with non-aligned size should have smaller last chunk")
    void testSplitFile_NonAlignedSize() throws IOException {
        int extraBytes = 100;
        byte[] data = new byte[2 * TEST_CHUNK_SIZE + extraBytes];
        new Random(42).nextBytes(data);
        UUID ownerId = UUID.randomUUID();

        List<ChunkData> chunks = chunkEngine.splitFile(
                new ByteArrayInputStream(data), data.length, ownerId);

        assertEquals(3, chunks.size());
        assertEquals(TEST_CHUNK_SIZE, chunks.get(0).getChunkSize());
        assertEquals(TEST_CHUNK_SIZE, chunks.get(1).getChunkSize());
        assertEquals(extraBytes, chunks.get(2).getChunkSize());
    }

    @Test
    @DisplayName("split then merge should recover original data")
    void testSplitMerge_RoundTrip() throws IOException {
        byte[] original = new byte[5 * TEST_CHUNK_SIZE + 500];
        new Random(42).nextBytes(original);
        UUID ownerId = UUID.randomUUID();

        List<ChunkData> chunks = chunkEngine.splitFile(
                new ByteArrayInputStream(original), original.length, ownerId);

        byte[] merged = chunkEngine.mergeChunksToBytes(chunks);
        assertArrayEquals(original, merged, "Merged data should match original");
    }

    @Test
    @DisplayName("calculateTotalChunks should handle edge cases correctly")
    void testCalculateTotalChunks() {
        assertEquals(0, chunkEngine.calculateTotalChunks(0));
        assertEquals(1, chunkEngine.calculateTotalChunks(1));
        assertEquals(1, chunkEngine.calculateTotalChunks(TEST_CHUNK_SIZE));
        assertEquals(2, chunkEngine.calculateTotalChunks(TEST_CHUNK_SIZE + 1));
        assertEquals(10, chunkEngine.calculateTotalChunks(10 * TEST_CHUNK_SIZE));
    }

    @Test
    @DisplayName("verifyChunkOrder should return true for valid contiguous indices")
    void testVerifyChunkOrder_Valid() throws IOException {
        byte[] data = new byte[3 * TEST_CHUNK_SIZE];
        new Random(42).nextBytes(data);

        List<ChunkData> chunks = chunkEngine.splitFile(
                new ByteArrayInputStream(data), data.length, UUID.randomUUID());

        assertTrue(chunkEngine.verifyChunkOrder(chunks));
    }

    @Test
    @DisplayName("verifyChunkOrder should return false for missing indices")
    void testVerifyChunkOrder_MissingIndex() {
        ChunkData chunk0 = ChunkData.builder().chunkIndex(0).build();
        ChunkData chunk2 = ChunkData.builder().chunkIndex(2).build();

        assertFalse(chunkEngine.verifyChunkOrder(Arrays.asList(chunk0, chunk2)),
                "Should detect missing index 1");
    }

    @Test
    @DisplayName("verifyChunkOrder should return false for duplicate indices")
    void testVerifyChunkOrder_DuplicateIndex() {
        ChunkData chunk0 = ChunkData.builder().chunkIndex(0).build();
        ChunkData chunk0dup = ChunkData.builder().chunkIndex(0).build();

        assertFalse(chunkEngine.verifyChunkOrder(Arrays.asList(chunk0, chunk0dup)),
                "Should detect duplicate index 0");
    }

    @Test
    @DisplayName("verifyChunkOrder should return false for empty list")
    void testVerifyChunkOrder_EmptyList() {
        assertFalse(chunkEngine.verifyChunkOrder(List.of()));
        assertFalse(chunkEngine.verifyChunkOrder(null));
    }

    @Test
    @DisplayName("each chunk should have valid SHA-256 hash and CRC32 checksum")
    void testChunkMetadata() throws IOException {
        byte[] data = new byte[2 * TEST_CHUNK_SIZE];
        new Random(42).nextBytes(data);

        List<ChunkData> chunks = chunkEngine.splitFile(
                new ByteArrayInputStream(data), data.length, UUID.randomUUID());

        for (ChunkData chunk : chunks) {
            // Verify SHA-256 is correct
            String recomputedHash = integrityService.computeSha256(chunk.getData());
            assertEquals(recomputedHash, chunk.getSha256Hash(),
                    "Chunk SHA-256 should match recomputed hash");

            // Verify checksum is correct
            String recomputedChecksum = integrityService.computeChecksum(chunk.getData());
            assertEquals(recomputedChecksum, chunk.getChecksum(),
                    "Chunk checksum should match recomputed checksum");

            // Verify UUID is set
            assertNotNull(chunk.getChunkId());
        }
    }

    @Test
    @DisplayName("mergeChunks should sort by index even if chunks arrive out of order")
    void testMergeChunks_OutOfOrder() throws IOException {
        byte[] original = new byte[3 * TEST_CHUNK_SIZE];
        new Random(42).nextBytes(original);

        List<ChunkData> chunks = chunkEngine.splitFile(
                new ByteArrayInputStream(original), original.length, UUID.randomUUID());

        // Reverse order
        List<ChunkData> reversed = Arrays.asList(chunks.get(2), chunks.get(0), chunks.get(1));

        byte[] merged = chunkEngine.mergeChunksToBytes(reversed);
        assertArrayEquals(original, merged, "Merge should handle out-of-order chunks");
    }

    @Test
    @DisplayName("mergeChunks with empty list should throw IllegalArgumentException")
    void testMergeChunks_EmptyList() {
        assertThrows(IllegalArgumentException.class, () ->
                chunkEngine.mergeChunksToBytes(List.of()));
    }

    @Test
    @DisplayName("getChunkSize should return configured chunk size")
    void testGetChunkSize() {
        assertEquals(TEST_CHUNK_SIZE, chunkEngine.getChunkSize());

        ChunkEngine defaultEngine = new ChunkEngine(integrityService);
        assertEquals(ChunkEngine.DEFAULT_CHUNK_SIZE, defaultEngine.getChunkSize());
    }
}
