package com.neurovault.backend.storage.engine;

import com.neurovault.backend.storage.container.ContainerManager;
import com.neurovault.backend.storage.exception.ChunkNotFoundException;
import com.neurovault.backend.storage.exception.ContainerException;
import com.neurovault.backend.storage.exception.StorageFullException;
import com.neurovault.backend.storage.model.ChunkMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StorageEngine}.
 * Creates a real container file in a temp directory for each test.
 */
class StorageEngineTest {

    @TempDir
    Path tempDir;

    private ContainerManager containerManager;
    private StorageEngine storageEngine;
    private Path containerPath;

    @BeforeEach
    void setUp() {
        containerManager = new ContainerManager();
        storageEngine = new StorageEngine(containerManager);
        containerPath = tempDir.resolve("test-host").resolve("storage.container");

        // Create a 2 MB container for testing
        containerManager.createContainer(containerPath, 2 * 1024 * 1024);
        storageEngine.initialize();
    }

    @AfterEach
    void tearDown() {
        if (containerManager.isOpen()) {
            containerManager.closeContainer();
        }
    }

    @Test
    void storeChunk_shouldWriteAndReturnMetadata() {
        UUID chunkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] data = "encrypted-data-block".getBytes();

        ChunkMetadata metadata = storageEngine.storeChunk(chunkId, ownerId, data);

        assertNotNull(metadata);
        assertEquals(chunkId, metadata.getChunkId());
        assertEquals(ownerId, metadata.getOwnerId());
        assertEquals(data.length, metadata.getChunkSize());
        assertNotNull(metadata.getSha256Hash());
        assertFalse(metadata.isDeleted());
    }

    @Test
    void storeChunk_shouldRejectDuplicateId() {
        UUID chunkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] data = "test-data".getBytes();

        storageEngine.storeChunk(chunkId, ownerId, data);

        assertThrows(ContainerException.class, () ->
                storageEngine.storeChunk(chunkId, ownerId, data));
    }

    @Test
    void readChunk_shouldReturnOriginalData() {
        UUID chunkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] originalData = "hello-neurovault-encrypted".getBytes();

        storageEngine.storeChunk(chunkId, ownerId, originalData);
        byte[] readData = storageEngine.readChunk(chunkId);

        assertArrayEquals(originalData, readData);
    }

    @Test
    void readChunk_shouldFailForNonExistent() {
        UUID randomId = UUID.randomUUID();
        assertThrows(ChunkNotFoundException.class, () ->
                storageEngine.readChunk(randomId));
    }

    @Test
    void deleteChunk_shouldSoftDelete() {
        UUID chunkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] data = "data-to-delete".getBytes();

        storageEngine.storeChunk(chunkId, ownerId, data);
        assertTrue(storageEngine.verifyChunkExists(chunkId));

        storageEngine.deleteChunk(chunkId);
        assertFalse(storageEngine.verifyChunkExists(chunkId));
    }

    @Test
    void deleteChunk_shouldFailForNonExistent() {
        UUID randomId = UUID.randomUUID();
        assertThrows(ChunkNotFoundException.class, () ->
                storageEngine.deleteChunk(randomId));
    }

    @Test
    void deleteChunk_shouldReduceUsedSpace() {
        UUID chunkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] data = new byte[1024]; // 1 KB

        storageEngine.storeChunk(chunkId, ownerId, data);
        long usedBefore = storageEngine.calculateUsedSpace();
        assertEquals(1024, usedBefore);

        storageEngine.deleteChunk(chunkId);
        long usedAfter = storageEngine.calculateUsedSpace();
        assertEquals(0, usedAfter);
    }

    @Test
    void listChunks_shouldReturnActiveChunksOnly() {
        UUID chunk1 = UUID.randomUUID();
        UUID chunk2 = UUID.randomUUID();
        UUID chunk3 = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] data = "test".getBytes();

        storageEngine.storeChunk(chunk1, ownerId, data);
        storageEngine.storeChunk(chunk2, ownerId, data);
        storageEngine.storeChunk(chunk3, ownerId, data);

        assertEquals(3, storageEngine.listChunks().size());

        storageEngine.deleteChunk(chunk2);

        List<ChunkMetadata> active = storageEngine.listChunks();
        assertEquals(2, active.size());
        assertTrue(active.stream().anyMatch(m -> m.getChunkId().equals(chunk1)));
        assertTrue(active.stream().anyMatch(m -> m.getChunkId().equals(chunk3)));
    }

    @Test
    void getChunkMetadata_shouldReturnCorrectMetadata() {
        UUID chunkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] data = "metadata-test".getBytes();

        storageEngine.storeChunk(chunkId, ownerId, data);

        ChunkMetadata metadata = storageEngine.getChunkMetadata(chunkId);
        assertEquals(chunkId, metadata.getChunkId());
        assertEquals(data.length, metadata.getChunkSize());
    }

    @Test
    void calculateUsedSpace_shouldSumActiveChunks() {
        UUID ownerId = UUID.randomUUID();

        storageEngine.storeChunk(UUID.randomUUID(), ownerId, new byte[100]);
        storageEngine.storeChunk(UUID.randomUUID(), ownerId, new byte[200]);
        storageEngine.storeChunk(UUID.randomUUID(), ownerId, new byte[300]);

        assertEquals(600, storageEngine.calculateUsedSpace());
    }

    @Test
    void calculateFreeSpace_shouldAccountForUsedSpace() {
        long initialFree = storageEngine.calculateFreeSpace();
        assertTrue(initialFree > 0);

        UUID ownerId = UUID.randomUUID();
        byte[] data = new byte[1024]; // 1 KB
        storageEngine.storeChunk(UUID.randomUUID(), ownerId, data);

        long afterStore = storageEngine.calculateFreeSpace();
        assertEquals(initialFree - 1024, afterStore);
    }

    @Test
    void checkCapacity_shouldReturnCorrectly() {
        assertTrue(storageEngine.checkCapacity(1024));
        assertFalse(storageEngine.checkCapacity(Long.MAX_VALUE));
    }

    @Test
    void verifyChunkExists_shouldReturnFalseForDeleted() {
        UUID chunkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] data = "exists-test".getBytes();

        storageEngine.storeChunk(chunkId, ownerId, data);
        assertTrue(storageEngine.verifyChunkExists(chunkId));

        storageEngine.deleteChunk(chunkId);
        assertFalse(storageEngine.verifyChunkExists(chunkId));
    }

    @Test
    void verifyChunkExists_shouldReturnFalseForUnknownId() {
        assertFalse(storageEngine.verifyChunkExists(UUID.randomUUID()));
    }

    @Test
    void multipleChunks_shouldStoreAndReadIndependently() {
        UUID ownerId = UUID.randomUUID();
        byte[][] chunks = new byte[5][];
        UUID[] ids = new UUID[5];

        for (int i = 0; i < 5; i++) {
            ids[i] = UUID.randomUUID();
            chunks[i] = ("chunk-data-" + i).getBytes();
            storageEngine.storeChunk(ids[i], ownerId, chunks[i]);
        }

        // Read each chunk and verify independence
        for (int i = 0; i < 5; i++) {
            byte[] readData = storageEngine.readChunk(ids[i]);
            assertArrayEquals(chunks[i], readData);
        }
    }

    @Test
    void storeChunk_shouldComputeCorrectSha256() {
        UUID chunkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] data = "sha256-test-data".getBytes();

        ChunkMetadata metadata = storageEngine.storeChunk(chunkId, ownerId, data);

        // SHA-256 should be a 64-character hex string
        assertNotNull(metadata.getSha256Hash());
        assertEquals(64, metadata.getSha256Hash().length());
        assertTrue(metadata.getSha256Hash().matches("[0-9a-f]+"));
    }

    @Test
    void metadataPersistence_shouldSurviveReinitialization() {
        UUID chunkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] data = "persistence-test".getBytes();

        storageEngine.storeChunk(chunkId, ownerId, data);

        // Close and reopen
        containerManager.closeContainer();
        containerManager.openContainer(containerPath);
        storageEngine.initialize();

        // The chunk should still be there
        assertTrue(storageEngine.verifyChunkExists(chunkId));
        byte[] readData = storageEngine.readChunk(chunkId);
        assertArrayEquals(data, readData);
    }
}
