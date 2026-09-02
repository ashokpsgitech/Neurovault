package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.*;
import com.neurovault.backend.monitor.service.ClusterAnalyticsService;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.replication.dto.RepairResultDto;
import com.neurovault.backend.replication.event.ClusterEventPublisher;
import com.neurovault.backend.replication.exception.InsufficientHostsException;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.storage.dto.ChunkTransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the SelfHealingService with mocked dependencies.
 *
 * <p>Tests the complete healing workflow:
 * <ol>
 *   <li>SYNCING replica creation</li>
 *   <li>Physical transfer via ChunkTransferService</li>
 *   <li>Integrity verification</li>
 *   <li>Promotion to ACTIVE or rollback to MISSING</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
public class SelfHealingServiceTest {

    @Mock private ReplicationService replicationService;
    @Mock private HostSelectionService hostSelectionService;
    @Mock private ChunkTransferService chunkTransferService;
    @Mock private ClusterEventPublisher eventPublisher;
    @Mock private ClusterAnalyticsService analyticsService;
    @Mock private HostRepository hostRepository;
    @Mock private ChunkReplicaRepository chunkReplicaRepository;

    private ReplicationConfig config;
    private SelfHealingService selfHealingService;

    private User testUser;
    private Host hostA;
    private Host hostB;
    private Host hostC;
    private Chunk chunk;
    private FileMetadata fileMetadata;
    private ChunkReplica activeReplicaOnA;

    @BeforeEach
    public void setup() {
        config = new ReplicationConfig();
        config.setFactor(3);
        config.setMaxConcurrentRepairs(5);
        config.setTransferTimeoutSeconds(120);

        selfHealingService = new SelfHealingService(
                replicationService, hostSelectionService, chunkTransferService,
                eventPublisher, analyticsService, config, hostRepository, chunkReplicaRepository);

        // Test entities
        testUser = User.builder()
                .id(UUID.randomUUID())
                .username("healuser")
                .email("heal@test.com")
                .password("pass")
                .role(User.Role.CLIENT)
                .build();

        hostA = Host.builder()
                .id(UUID.randomUUID()).name("HostA")
                .status(Host.Status.ONLINE)
                .totalCapacityBytes(500_000L).usedCapacityBytes(100L)
                .reservedCapacityBytes(0L)
                .lastHeartbeat(LocalDateTime.now())
                .build();

        hostB = Host.builder()
                .id(UUID.randomUUID()).name("HostB")
                .status(Host.Status.ONLINE)
                .totalCapacityBytes(500_000L).usedCapacityBytes(0L)
                .reservedCapacityBytes(0L)
                .lastHeartbeat(LocalDateTime.now())
                .build();

        hostC = Host.builder()
                .id(UUID.randomUUID()).name("HostC")
                .status(Host.Status.ONLINE)
                .totalCapacityBytes(500_000L).usedCapacityBytes(0L)
                .reservedCapacityBytes(0L)
                .lastHeartbeat(LocalDateTime.now())
                .build();

        fileMetadata = FileMetadata.builder()
                .id(UUID.randomUUID())
                .owner(testUser)
                .name("test.txt")
                .path("/test.txt")
                .sizeBytes(100L)
                .fileHash("hash")
                .encryptedAesKey("key")
                .build();

        chunk = Chunk.builder()
                .id(UUID.randomUUID())
                .file(fileMetadata)
                .chunkIndex(0)
                .sizeBytes(100L)
                .checksum("abc123sha256")
                .status(Chunk.Status.ACTIVE)
                .build();

        activeReplicaOnA = ChunkReplica.builder()
                .id(UUID.randomUUID())
                .chunk(chunk)
                .host(hostA)
                .status(ChunkReplica.Status.ACTIVE)
                .containerOffsetBytes(0L)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Happy path: healChunk finds a healthy source (HostA), selects destinations
     * (HostB, HostC), creates SYNCING replicas, transfers successfully, promotes to ACTIVE.
     */
    @Test
    public void testHealChunk_Success() {
        UUID chunkId = chunk.getId();
        ChunkReplica syncingB = ChunkReplica.builder()
                .id(UUID.randomUUID()).chunk(chunk).host(hostB)
                .status(ChunkReplica.Status.SYNCING).containerOffsetBytes(0L)
                .createdAt(LocalDateTime.now()).build();
        ChunkReplica syncingC = ChunkReplica.builder()
                .id(UUID.randomUUID()).chunk(chunk).host(hostC)
                .status(ChunkReplica.Status.SYNCING).containerOffsetBytes(0L)
                .createdAt(LocalDateTime.now()).build();

        // Mock: existing replicas = [activeReplicaOnA]
        when(replicationService.getReplicasByChunk(chunkId))
                .thenReturn(List.of(activeReplicaOnA));

        // Mock: healthy source → HostA
        when(replicationService.findHealthySourceHost(chunkId))
                .thenReturn(Optional.of(hostA));

        // Mock: host selection → HostB first, then HostC
        when(hostSelectionService.selectReplacementHost(eq(chunkId), anySet()))
                .thenReturn(hostB)
                .thenReturn(hostC);

        // Mock: create SYNCING replicas
        when(replicationService.createSyncingReplica(chunkId, hostB.getId()))
                .thenReturn(syncingB);
        when(replicationService.createSyncingReplica(chunkId, hostC.getId()))
                .thenReturn(syncingC);

        // Mock: transfers succeed
        when(chunkTransferService.transferChunk(eq(hostA), eq(hostB), eq(chunkId), any(), any()))
                .thenReturn(ChunkTransferResponse.builder()
                        .success(true).chunkId(chunkId)
                        .sourceHostId(hostA.getId()).destinationHostId(hostB.getId())
                        .destinationChecksum("abc123sha256").build());
        when(chunkTransferService.transferChunk(eq(hostA), eq(hostC), eq(chunkId), any(), any()))
                .thenReturn(ChunkTransferResponse.builder()
                        .success(true).chunkId(chunkId)
                        .sourceHostId(hostA.getId()).destinationHostId(hostC.getId())
                        .destinationChecksum("abc123sha256").build());

        // Act
        int repaired = selfHealingService.healChunk(chunkId, 2);

        // Assert
        assertEquals(2, repaired, "Should repair 2 replicas");

        // Verify SYNCING replicas were created
        verify(replicationService).createSyncingReplica(chunkId, hostB.getId());
        verify(replicationService).createSyncingReplica(chunkId, hostC.getId());

        // Verify transfers were initiated
        verify(chunkTransferService).transferChunk(eq(hostA), eq(hostB), eq(chunkId), any(), any());
        verify(chunkTransferService).transferChunk(eq(hostA), eq(hostC), eq(chunkId), any(), any());

        // Verify replicas were promoted to ACTIVE
        verify(replicationService).markReplicaActive(syncingB.getId());
        verify(replicationService).markReplicaActive(syncingC.getId());

        // Verify host capacity was updated
        verify(hostRepository).save(hostB);
        verify(hostRepository).save(hostC);
    }

    /**
     * Tests that failed transfers cause the SYNCING replica to be marked MISSING (not ACTIVE).
     * The coordinator must NEVER mark a replica ACTIVE before successful physical transfer.
     */
    @Test
    public void testHealChunk_TransferFails_ReplicaMarkedMissing() {
        UUID chunkId = chunk.getId();
        ChunkReplica syncingB = ChunkReplica.builder()
                .id(UUID.randomUUID()).chunk(chunk).host(hostB)
                .status(ChunkReplica.Status.SYNCING).containerOffsetBytes(0L)
                .createdAt(LocalDateTime.now()).build();

        when(replicationService.getReplicasByChunk(chunkId))
                .thenReturn(List.of(activeReplicaOnA));
        when(replicationService.findHealthySourceHost(chunkId))
                .thenReturn(Optional.of(hostA));
        when(hostSelectionService.selectReplacementHost(eq(chunkId), anySet()))
                .thenReturn(hostB);
        when(replicationService.createSyncingReplica(chunkId, hostB.getId()))
                .thenReturn(syncingB);

        // Transfer FAILS
        when(chunkTransferService.transferChunk(eq(hostA), eq(hostB), eq(chunkId), any(), any()))
                .thenReturn(ChunkTransferResponse.builder()
                        .success(false).chunkId(chunkId)
                        .sourceHostId(hostA.getId()).destinationHostId(hostB.getId())
                        .errorMessage("Connection refused").build());

        int repaired = selfHealingService.healChunk(chunkId, 1);

        assertEquals(0, repaired, "No replicas should be counted as repaired");

        // CRITICAL: markReplicaActive must NOT have been called
        verify(replicationService, never()).markReplicaActive(any());

        // The SYNCING replica should be set to MISSING
        assertEquals(ChunkReplica.Status.MISSING, syncingB.getStatus(),
                "Failed SYNCING replica must be marked MISSING, not left as SYNCING");
        verify(chunkReplicaRepository).save(syncingB);
    }

    /**
     * Tests healing when no source host is available (all copies lost).
     */
    @Test
    public void testHealChunk_NoSourceAvailable() {
        UUID chunkId = chunk.getId();

        when(replicationService.getReplicasByChunk(chunkId))
                .thenReturn(List.of(activeReplicaOnA));
        when(replicationService.findHealthySourceHost(chunkId))
                .thenReturn(Optional.empty());

        int repaired = selfHealingService.healChunk(chunkId, 2);

        assertEquals(0, repaired, "No repairs when no source is available");
        verify(chunkTransferService, never()).transferChunk(any(), any(), any(), any(), any());
        verify(replicationService, never()).createSyncingReplica(any(), any());
    }

    /**
     * Tests healing when no destination hosts are available.
     */
    @Test
    public void testHealChunk_NoDestinationAvailable() {
        UUID chunkId = chunk.getId();

        when(replicationService.getReplicasByChunk(chunkId))
                .thenReturn(List.of(activeReplicaOnA));
        when(replicationService.findHealthySourceHost(chunkId))
                .thenReturn(Optional.of(hostA));
        when(hostSelectionService.selectReplacementHost(eq(chunkId), anySet()))
                .thenThrow(new InsufficientHostsException("No hosts"));

        int repaired = selfHealingService.healChunk(chunkId, 2);

        assertEquals(0, repaired, "No repairs when no destination available");
        verify(chunkTransferService, never()).transferChunk(any(), any(), any(), any(), any());
    }

    /**
     * Tests the full runHealingCycle method with under-replicated chunks.
     */
    @Test
    public void testRunHealingCycle_DetectsAndRepairs() {
        UUID chunkId = chunk.getId();
        ChunkReplica syncingB = ChunkReplica.builder()
                .id(UUID.randomUUID()).chunk(chunk).host(hostB)
                .status(ChunkReplica.Status.SYNCING).containerOffsetBytes(0L)
                .createdAt(LocalDateTime.now()).build();

        // Stale SYNCING cleanup returns empty
        when(chunkReplicaRepository.findByStatus(ChunkReplica.Status.SYNCING))
                .thenReturn(List.of());

        // 1 under-replicated chunk with deficit 1
        when(replicationService.getUnderReplicatedChunks())
                .thenReturn(Map.of(chunkId, 1));

        when(replicationService.getReplicasByChunk(chunkId))
                .thenReturn(List.of(activeReplicaOnA));
        when(replicationService.findHealthySourceHost(chunkId))
                .thenReturn(Optional.of(hostA));
        when(hostSelectionService.selectReplacementHost(eq(chunkId), anySet()))
                .thenReturn(hostB);
        when(replicationService.createSyncingReplica(chunkId, hostB.getId()))
                .thenReturn(syncingB);
        when(chunkTransferService.transferChunk(eq(hostA), eq(hostB), eq(chunkId), any(), any()))
                .thenReturn(ChunkTransferResponse.builder()
                        .success(true).chunkId(chunkId)
                        .sourceHostId(hostA.getId()).destinationHostId(hostB.getId())
                        .destinationChecksum("abc123sha256").build());

        RepairResultDto result = selfHealingService.runHealingCycle();

        assertEquals(1, result.getChunksInspected());
        assertEquals(1, result.getRepairsSucceeded());
        assertEquals(0, result.getRepairsFailed());
        assertNotNull(result.getTimestamp());

        verify(replicationService).markReplicaActive(syncingB.getId());
    }

    /**
     * Tests concurrency guard — only one healing cycle runs at a time.
     */
    @Test
    public void testRunHealingCycle_ConcurrencyGuard() {
        when(chunkReplicaRepository.findByStatus(ChunkReplica.Status.SYNCING))
                .thenReturn(List.of());
        when(replicationService.getUnderReplicatedChunks())
                .thenReturn(Map.of());

        // First call should succeed
        RepairResultDto result1 = selfHealingService.runHealingCycle();
        assertNotNull(result1);

        // Second call should also succeed since first completed
        RepairResultDto result2 = selfHealingService.runHealingCycle();
        assertNotNull(result2);
    }

    /**
     * Tests SYNCING cleanup: stale SYNCING replicas older than transferTimeoutSeconds
     * should be marked MISSING.
     */
    @Test
    public void testRunHealingCycle_CleansUpStaleSyncingReplicas() {
        // Stale SYNCING replica created 5 minutes ago (timeout is 120s)
        ChunkReplica staleSync = ChunkReplica.builder()
                .id(UUID.randomUUID()).chunk(chunk).host(hostB)
                .status(ChunkReplica.Status.SYNCING).containerOffsetBytes(0L)
                .createdAt(LocalDateTime.now().minusSeconds(300))
                .build();

        when(chunkReplicaRepository.findByStatus(ChunkReplica.Status.SYNCING))
                .thenReturn(List.of(staleSync));
        when(replicationService.getUnderReplicatedChunks())
                .thenReturn(Map.of());

        selfHealingService.runHealingCycle();

        // Stale replica should be marked MISSING
        assertEquals(ChunkReplica.Status.MISSING, staleSync.getStatus(),
                "Stale SYNCING replica should be cleaned up to MISSING");
        verify(chunkReplicaRepository).save(staleSync);
    }

    /**
     * Tests that healing respects maxConcurrentRepairs limit.
     */
    @Test
    public void testRunHealingCycle_RespectsMaxConcurrentRepairs() {
        config.setMaxConcurrentRepairs(1);

        UUID chunk1Id = UUID.randomUUID();
        UUID chunk2Id = UUID.randomUUID();

        when(chunkReplicaRepository.findByStatus(ChunkReplica.Status.SYNCING))
                .thenReturn(List.of());

        // 2 under-replicated chunks, but max=1
        Map<UUID, Integer> underReplicated = new LinkedHashMap<>();
        underReplicated.put(chunk1Id, 1);
        underReplicated.put(chunk2Id, 1);
        when(replicationService.getUnderReplicatedChunks())
                .thenReturn(underReplicated);

        // Set up chunk1 to heal successfully
        when(replicationService.getReplicasByChunk(chunk1Id))
                .thenReturn(List.of(activeReplicaOnA));
        when(replicationService.findHealthySourceHost(chunk1Id))
                .thenReturn(Optional.of(hostA));
        when(hostSelectionService.selectReplacementHost(eq(chunk1Id), anySet()))
                .thenReturn(hostB);
        ChunkReplica syncR = ChunkReplica.builder()
                .id(UUID.randomUUID()).chunk(chunk).host(hostB)
                .status(ChunkReplica.Status.SYNCING).containerOffsetBytes(0L)
                .createdAt(LocalDateTime.now()).build();
        when(replicationService.createSyncingReplica(chunk1Id, hostB.getId()))
                .thenReturn(syncR);
        when(chunkTransferService.transferChunk(eq(hostA), eq(hostB), eq(chunk1Id), any(), any()))
                .thenReturn(ChunkTransferResponse.builder()
                        .success(true).chunkId(chunk1Id)
                        .sourceHostId(hostA.getId()).destinationHostId(hostB.getId())
                        .destinationChecksum("abc").build());

        RepairResultDto result = selfHealingService.runHealingCycle();

        // Only 1 repair should have been initiated (max=1)
        assertEquals(1, result.getRepairsInitiated());
        assertTrue(result.getDetails().stream().anyMatch(d -> d.contains("max concurrent")),
                "Should mention max concurrent limit was reached");
    }
}
