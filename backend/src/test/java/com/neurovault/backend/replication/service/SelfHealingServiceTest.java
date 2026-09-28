package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.*;
import com.neurovault.backend.monitor.service.ClusterAnalyticsService;
import com.neurovault.backend.replication.dto.RepairResultDto;
import com.neurovault.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_selfhealing;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Transactional
public class SelfHealingServiceTest {

    @Autowired
    private SelfHealingService selfHealingService;

    @Autowired
    private ReplicationService replicationService;

    @Autowired
    private ClusterAnalyticsService analyticsService;

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

    @Autowired
    private com.neurovault.backend.repository.ReplicationTaskRepository taskRepository;

    private User testUser;
    private Host hostA;
    private Host hostB;
    private Host hostC;
    private Chunk chunk;

    @BeforeEach
    public void setup() {
        chunkReplicaRepository.deleteAll();
        chunkRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        containerRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("healuser")
                .email("heal@example.com")
                .password("password")
                .role(User.Role.CLIENT)
                .build();
        userRepository.save(testUser);

        // Host A, B, C are online and have active storage containers
        hostA = createHealthyHost("HostA");
        hostB = createHealthyHost("HostB");
        hostC = createHealthyHost("HostC");

        FileMetadata fileMetadata = fileMetadataRepository.save(FileMetadata.builder()
                .owner(testUser)
                .name("test.txt")
                .path("/path/test.txt")
                .sizeBytes(100L)
                .fileHash("hash123")
                .encryptedAesKey("key123")
                .build());

        chunk = chunkRepository.save(Chunk.builder()
                .file(fileMetadata)
                .chunkIndex(0)
                .sizeBytes(100L)
                .checksum("chksum123")
                .status(Chunk.Status.ACTIVE)
                .build());
    }

    private Host createHealthyHost(String name) {
        Host host = hostRepository.save(Host.builder()
                .owner(testUser)
                .name(name)
                .totalCapacityBytes(5000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        containerRepository.save(StorageContainer.builder()
                .host(host)
                .filePath("/tmp/" + name)
                .totalSize(5000L)
                .status(StorageContainer.Status.ACTIVE)
                .build());

        return host;
    }

    @Test
    public void testHealChunk_NoActiveSource_UnrecoverableCase() {
        // No replicas exist at all - no ACTIVE source.
        // NV-P0-01 fix: healChunk must refuse to create phantom ACTIVE replicas.
        // Previously this would call assignReplicas() and create fake ACTIVE replicas.
        int repaired = selfHealingService.healChunk(chunk.getId(), 2);

        // Must return 0 - cannot heal without a verified physical source
        assertEquals(0, repaired);

        // Must NOT have created any phantom ACTIVE replicas
        long activeReplicas = chunkReplicaRepository.findByChunkId(chunk.getId()).stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                .count();
        assertEquals(0, activeReplicas);
    }

    @Test
    public void testHealChunk_WithActiveSource_TransferFails_NoPhantomReplicas() {
        // Create a legitimate ACTIVE source replica directly (simulates data plane having stored bytes)
        ChunkReplica sourceReplica = chunkReplicaRepository.save(ChunkReplica.builder()
                .chunk(chunk)
                .host(hostA)
                .containerOffsetBytes(0L)
                .status(ChunkReplica.Status.ACTIVE)
                .build());

        // healChunk will find an ACTIVE source and attempt physical copyChunk.
        // In this integration test, the physical containers don't exist so copyChunk will throw.
        // NV-P0-01 fix: the failure must NOT create phantom ACTIVE replicas.
        int repaired = selfHealingService.healChunk(chunk.getId(), 2);

        // Physical transfer fails -> 0 successfully repaired
        assertEquals(0, repaired);

        // Only the original sourceReplica should remain ACTIVE - no phantom replicas
        long activeReplicas = chunkReplicaRepository.findByChunkId(chunk.getId()).stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                .count();
        assertEquals(1, activeReplicas, "Only the original ACTIVE source replica should remain - no phantom replicas");
    }

    @Test
    public void testRunHealingCycle_NoActiveSource_UnrecoverablePath() {
        // No replicas exist - no ACTIVE source anywhere.
        // NV-P0-01 fix: cycle must detect unrecoverable state and NOT create phantom replicas.
        RepairResultDto result = selfHealingService.runHealingCycle();

        // The chunk is under-replicated (deficit=3)
        assertEquals(1, result.getChunksInspected());
        // Since sourceHostId == null, we return 0 repaired immediately
        assertEquals(3, result.getRepairsInitiated());
        assertEquals(0, result.getRepairsSucceeded());
        assertEquals(3, result.getRepairsFailed());

        // No phantom replicas created
        long activeReplicas = chunkReplicaRepository.findByChunkId(chunk.getId()).stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                .count();
        assertEquals(0, activeReplicas, "No phantom ACTIVE replicas should be created without physical source");
    }

    @Test
    public void testRunHealingCycle_InsufficientHosts() {
        // Put HostB and HostC offline to simulate limited replacement options.
        hostB.setStatus(Host.Status.OFFLINE);
        hostRepository.save(hostB);
        hostC.setStatus(Host.Status.OFFLINE);
        hostRepository.save(hostC);

        // Without any ACTIVE source, the cycle should detect unrecoverable state.
        RepairResultDto result = selfHealingService.runHealingCycle();

        assertEquals(1, result.getChunksInspected());
        // 0 repaired because no ACTIVE source replica exists
        assertEquals(0, result.getRepairsSucceeded());
    }
}
