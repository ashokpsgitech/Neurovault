package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.*;
import com.neurovault.backend.repository.*;
import com.neurovault.backend.storage.dto.ChunkTransferResponse;
import com.neurovault.backend.storage.dto.StoreChunkRequest;
import com.neurovault.backend.storage.model.StorageReservationSize;
import com.neurovault.backend.storage.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ChunkTransferService — tests physical chunk transfer
 * between host containers with integrity verification.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_transfer;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "neurovault.storage.base-dir=${java.io.tmpdir}/neurovault-transfer-test",
        "neurovault.replication.scheduler-interval-ms=9999999",
        "neurovault.host.heartbeat-check-interval-ms=9999999"
})
@ActiveProfiles("test")
public class ChunkTransferServiceTest {

    // Mock schedulers to prevent background interference
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.neurovault.backend.scheduler.ClusterMaintenanceScheduler maintenanceScheduler;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.neurovault.backend.host.scheduler.HeartbeatScheduler heartbeatScheduler;

    @Autowired
    private ChunkTransferService chunkTransferService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HostRepository hostRepository;

    @Autowired
    private StorageContainerRepository containerRepository;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ChunkReplicaRepository chunkReplicaRepository;

    private User testUser;
    private Host sourceHost;
    private Host destHost;
    private Chunk chunk;
    private UUID chunkId;
    private byte[] chunkData;
    private Path storageBaseDir;

    @BeforeEach
    public void setup() {
        chunkReplicaRepository.deleteAll();
        chunkRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        containerRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        storageBaseDir = Path.of(System.getProperty("java.io.tmpdir"), "neurovault-transfer-test");

        testUser = userRepository.save(User.builder()
                .username("transferuser")
                .email("transfer@example.com")
                .password("password")
                .role(User.Role.CLIENT)
                .build());

        sourceHost = createHostWithStorage("SourceHost");
        destHost = createHostWithStorage("DestHost");

        FileMetadata file = fileMetadataRepository.save(FileMetadata.builder()
                .owner(testUser)
                .name("transfer-test.dat")
                .path("/path/transfer-test.dat")
                .sizeBytes(256L)
                .fileHash("filehash")
                .encryptedAesKey("aeskey")
                .build());

        chunkData = new byte[256];
        for (int i = 0; i < chunkData.length; i++) {
            chunkData[i] = (byte) (i % 127);
        }

        chunk = chunkRepository.save(Chunk.builder()
                .file(file)
                .chunkIndex(0)
                .sizeBytes(256L)
                .checksum("test-checksum")
                .status(Chunk.Status.ACTIVE)
                .build());
        chunkId = chunk.getId();

        // Store chunk physically on source host
        storageService.storeChunk(sourceHost.getId(), StoreChunkRequest.builder()
                .chunkId(chunkId)
                .ownerId(testUser.getId())
                .data(chunkData)
                .build());
    }

    @AfterEach
    public void cleanup() throws IOException {
        if (storageBaseDir != null && Files.exists(storageBaseDir)) {
            Files.walk(storageBaseDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException e) { /* ignore */ }
                    });
        }
    }

    private Host createHostWithStorage(String name) {
        Host host = hostRepository.save(Host.builder()
                .owner(testUser)
                .name(name)
                .totalCapacityBytes(500_000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        storageService.createStorage(host.getId(), StorageReservationSize.MB_500, null);
        return host;
    }

    /**
     * Tests successful chunk transfer: data read from source, written to destination,
     * and integrity verified.
     */
    @Test
    public void testTransferChunk_Success() {
        ChunkTransferResponse response = chunkTransferService.transferChunk(
                sourceHost, destHost, chunkId, null, testUser.getId());

        assertTrue(response.isSuccess(), "Transfer should succeed");
        assertEquals(chunkId, response.getChunkId());
        assertEquals(sourceHost.getId(), response.getSourceHostId());
        assertEquals(destHost.getId(), response.getDestinationHostId());
        assertNotNull(response.getDestinationChecksum(), "Should have destination checksum");
        assertNull(response.getErrorMessage(), "Should have no error message");

        // Verify data was actually written to destination
        byte[] destData = storageService.readChunk(destHost.getId(), chunkId);
        assertNotNull(destData, "Destination should have chunk data");
        assertArrayEquals(chunkData, destData,
                "Destination data should match source data exactly");
    }

    /**
     * Tests transfer with checksum verification — matching checksum.
     */
    @Test
    public void testTransferChunk_WithMatchingChecksum() {
        // Compute actual SHA-256 of chunk data
        String expectedHash = computeSha256(chunkData);

        ChunkTransferResponse response = chunkTransferService.transferChunk(
                sourceHost, destHost, chunkId, expectedHash, testUser.getId());

        assertTrue(response.isSuccess(), "Transfer with matching checksum should succeed");
        assertEquals(expectedHash, response.getDestinationChecksum(),
                "Destination checksum should match expected");
    }

    /**
     * Tests transfer with wrong expected checksum — should fail integrity check.
     */
    @Test
    public void testTransferChunk_IntegrityMismatch() {
        String wrongChecksum = "0000000000000000000000000000000000000000000000000000000000000000";

        ChunkTransferResponse response = chunkTransferService.transferChunk(
                sourceHost, destHost, chunkId, wrongChecksum, testUser.getId());

        assertFalse(response.isSuccess(), "Transfer should fail with wrong checksum");
        assertNotNull(response.getErrorMessage(), "Should have error message");
    }

    /**
     * Tests transfer when source host's container has no chunks — should fail gracefully.
     * Uses destHost (which has an empty container) as the source to ensure failure.
     */
    @Test
    public void testTransferChunk_EmptySourceContainer() {
        // destHost has an empty container — reading any chunk from it should fail
        ChunkTransferResponse response = chunkTransferService.transferChunk(
                destHost, sourceHost, chunkId, null, testUser.getId());

        assertFalse(response.isSuccess(), "Transfer should fail when source container is empty");
        assertNotNull(response.getErrorMessage());
    }

    private String computeSha256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
