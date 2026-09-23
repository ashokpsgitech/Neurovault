package com.neurovault.backend.integration;

import com.neurovault.backend.entity.Chunk;
import com.neurovault.backend.entity.FileMetadata;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.ReplicationTask;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.replication.exception.InsufficientCapacityException;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.ChunkRepository;
import com.neurovault.backend.repository.FileMetadataRepository;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.ReplicationTaskRepository;
import com.neurovault.backend.repository.UserRepository;
import com.neurovault.backend.replication.service.ReplicationTransferService;
import com.neurovault.backend.storage.container.ContainerContext;
import com.neurovault.backend.storage.container.ContainerManager;
import com.neurovault.backend.storage.engine.StorageEngine;
import com.neurovault.backend.storage.exception.CorruptedChunkException;
import com.neurovault.backend.storage.model.ChunkMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class FailureInjectionIntegrationTest {

    @TempDir
    Path tempDir;

    @Autowired
    private HostRepository hostRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileMetadataRepository fileRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ChunkReplicaRepository replicaRepository;

    @Autowired
    private ReplicationTaskRepository taskRepository;

    @Autowired
    private ContainerManager containerManager;

    @Autowired
    private StorageEngine storageEngine;

    @Autowired
    private ReplicationTransferService replicationTransferService;

    private String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Container Write Interruption and Crash Recovery
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Write interruption leaves previously written chunks intact and recoverable")
    void testContainerWriteInterruptionAndCrashRecovery() throws Exception {
        UUID hostId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Path containerPath = tempDir.resolve("crash-" + hostId + ".container");

        containerManager.createContainer(hostId, containerPath, 10 * 1024 * 1024L);
        storageEngine.initialize(hostId);
        ContainerContext ctx = containerManager.getContext(hostId);
        assertNotNull(ctx);

        // 1. Write chunk 1 cleanly
        UUID chunk1Id = UUID.randomUUID();
        byte[] chunk1Data = "Solid first chunk payload".getBytes(StandardCharsets.UTF_8);
        String chunk1Hash = sha256(chunk1Data);
        ChunkMetadata meta1 = storageEngine.storeChunkStream(hostId, chunk1Id, ownerId, new ByteArrayInputStream(chunk1Data), chunk1Data.length, chunk1Hash);
        assertNotNull(meta1);

        // 2. Simulate write interruption during chunk 2
        // Write raw uncommitted bytes directly into the FileChannel at the end without updating the index
        FileChannel channel = ctx.getFileChannel();
        long corruptOffset = channel.size();
        ByteBuffer partialBuffer = ByteBuffer.wrap("PARTIAL_INTERRUPTED_WRITE".getBytes(StandardCharsets.UTF_8));
        channel.write(partialBuffer, corruptOffset);
        channel.force(true);

        // 3. Simulate process crash by closing and reopening the container
        containerManager.closeContainer(hostId);
        containerManager.openContainer(hostId, containerPath);
        storageEngine.initialize(hostId);

        // 4. Verify chunk 1 is still fully readable and matches hash
        byte[] readBackChunk1 = storageEngine.readChunk(hostId, chunk1Id);
        assertArrayEquals(chunk1Data, readBackChunk1);

        // 5. Verify writing chunk 3 succeeds and works normally
        UUID chunk3Id = UUID.randomUUID();
        byte[] chunk3Data = "Third chunk written after crash recovery".getBytes(StandardCharsets.UTF_8);
        String chunk3Hash = sha256(chunk3Data);
        ChunkMetadata meta3 = storageEngine.storeChunkStream(hostId, chunk3Id, ownerId, new ByteArrayInputStream(chunk3Data), chunk3Data.length, chunk3Hash);
        assertNotNull(meta3);

        byte[] readBackChunk3 = storageEngine.readChunk(hostId, chunk3Id);
        assertArrayEquals(chunk3Data, readBackChunk3);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Corrupted Metadata Index CRC Recovery
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Detects CRC checksum corruption and prevents reading corrupted chunk")
    void testCorruptedMetadataIndexCrcRecovery() throws Exception {
        UUID hostId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Path containerPath = tempDir.resolve("corrupt-" + hostId + ".container");

        containerManager.createContainer(hostId, containerPath, 10 * 1024 * 1024L);
        storageEngine.initialize(hostId);

        UUID chunkId = UUID.randomUUID();
        byte[] chunkData = "Important chunk to protect against bit-rot".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(chunkData);
        ChunkMetadata meta = storageEngine.storeChunkStream(hostId, chunkId, ownerId, new ByteArrayInputStream(chunkData), chunkData.length, hash);

        // Close container cleanly
        containerManager.closeContainer(hostId);

        // Intentionally tamper with 1 byte in the container file at the payload offset
        try (RandomAccessFile raf = new RandomAccessFile(containerPath.toFile(), "rw")) {
            raf.seek(meta.getOffset());
            byte original = raf.readByte();
            raf.seek(meta.getOffset());
            raf.writeByte(original ^ 0xFF);
        }

        // Reopen container
        containerManager.openContainer(hostId, containerPath);
        storageEngine.initialize(hostId);

        // Attempting to read corrupted chunk must throw CorruptedChunkException
        assertThrows(CorruptedChunkException.class, () -> {
            storageEngine.readChunk(hostId, chunkId);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Checksum Mismatch Detection
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Streaming write rejects chunks when content hash does not match expected checksum")
    void testChecksumMismatchDetection() throws Exception {
        UUID hostId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Path containerPath = tempDir.resolve("mismatch-" + hostId + ".container");

        containerManager.createContainer(hostId, containerPath, 10 * 1024 * 1024L);
        storageEngine.initialize(hostId);

        UUID chunkId = UUID.randomUUID();
        byte[] actualData = "Actual physical bytes".getBytes(StandardCharsets.UTF_8);
        String falsifiedChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // Empty string hash

        assertThrows(CorruptedChunkException.class, () -> {
            storageEngine.storeChunkStream(hostId, chunkId, ownerId, new ByteArrayInputStream(actualData), actualData.length, falsifiedChecksum);
        });

        // Ensure chunk was not recorded in index
        assertFalse(storageEngine.hasChunk(hostId, chunkId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Target Disk Full & Capacity Reservation Rollback
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Transactional
    @DisplayName("Insufficient host capacity throws InsufficientCapacityException and rolls back reservation")
    void testTargetDiskFullAndCapacityReservationRollback() {
        User user = User.builder()
                .username("test-user-" + UUID.randomUUID())
                .email("test-" + UUID.randomUUID() + "@example.com")
                .password("hash123")
                .role(User.Role.CLIENT)
                .build();
        user = userRepository.saveAndFlush(user);

        FileMetadata file = FileMetadata.builder()
                .owner(user)
                .name("test.bin")
                .path("/test.bin")
                .sizeBytes(100L)
                .encryptedAesKey("key123")
                .fileHash("hash123")
                .build();
        file = fileRepository.saveAndFlush(file);

        Chunk chunk = Chunk.builder()
                .file(file)
                .chunkIndex(0)
                .sizeBytes(100L)
                .checksum("chk123")
                .status(Chunk.Status.ACTIVE)
                .build();
        chunk = chunkRepository.saveAndFlush(chunk);

        Host host = Host.builder()
                .name("Low-Capacity-Host")
                .publicIp("127.0.0.1")
                .status(Host.Status.ONLINE)
                .totalCapacityBytes(500L)
                .usedCapacityBytes(400L)
                .reservedCapacityBytes(50L) // Remaining available = 500 - (400 + 50) = 50 bytes
                .build();
        host = hostRepository.saveAndFlush(host);

        UUID targetHostId = host.getId();
        UUID sourceHostId = UUID.randomUUID();
        UUID chunkId = chunk.getId();

        // Attempt copyChunk requiring 100 bytes (exceeds 50 bytes available)
        assertThrows(InsufficientCapacityException.class, () -> {
            replicationTransferService.copyChunk(sourceHostId, targetHostId, chunkId, "token");
        });

        // Verify host capacity was rolled back and not modified
        Host reloadedHost = hostRepository.findById(targetHostId).orElseThrow();
        assertEquals(400L, reloadedHost.getUsedCapacityBytes());
        assertEquals(50L, reloadedHost.getReservedCapacityBytes());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Expired Lease Recovery
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Transactional
    @DisplayName("Expired task lease is recovered and claimed by another worker")
    void testExpiredLeaseRecovery() {
        UUID chunkId = UUID.randomUUID();
        UUID targetHostId = UUID.randomUUID();

        // Create a task that was leased to dead-worker but lease expired 10 minutes ago
        ReplicationTask task = ReplicationTask.builder()
                .chunkId(chunkId)
                .targetHostId(targetHostId)
                .status(ReplicationTask.Status.CLAIMED)
                .workerId("worker-dead-99")
                .attemptCount(1)
                .leaseExpiresAt(LocalDateTime.now().minusMinutes(10))
                .build();
        task = taskRepository.saveAndFlush(task);

        // Live worker attempts atomic claim
        LocalDateTime newLease = LocalDateTime.now().plusMinutes(5);
        int claimedCount = taskRepository.claimTaskAtomic(
                task.getId(),
                "worker-alive-01",
                newLease,
                LocalDateTime.now()
        );

        assertEquals(1, claimedCount, "Worker-alive must successfully claim the expired lease");

        // Verify task state in database
        ReplicationTask claimedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(ReplicationTask.Status.CLAIMED, claimedTask.getStatus());
        assertEquals("worker-alive-01", claimedTask.getWorkerId());
        assertEquals(2, claimedTask.getAttemptCount());
        assertTrue(claimedTask.getLeaseExpiresAt().isAfter(LocalDateTime.now()));
    }
}
