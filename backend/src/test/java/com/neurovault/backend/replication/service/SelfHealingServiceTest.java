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
    public void testHealChunk_Success() {
        // Place 1 replica on HostA. Deficit = 2
        replicationService.assignReplicas(chunk.getId(), List.of(hostA.getId()));

        int repaired = selfHealingService.healChunk(chunk.getId(), 2);
        assertEquals(2, repaired);

        // Verify active replicas count is now 3
        long activeReplicas = chunkReplicaRepository.findByChunkId(chunk.getId()).stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                .count();
        assertEquals(3, activeReplicas);
    }

    @Test
    public void testRunHealingCycle_Success() {
        // Deficit = 3 (since no replicas exist)
        RepairResultDto result = selfHealingService.runHealingCycle();

        assertEquals(1, result.getChunksInspected());
        assertEquals(3, result.getRepairsInitiated());
        assertEquals(3, result.getRepairsSucceeded());
        assertEquals(0, result.getRepairsFailed());

        // Verify replicas placed across all 3 hosts
        List<ChunkReplica> replicas = chunkReplicaRepository.findByChunkId(chunk.getId());
        assertEquals(3, replicas.size());
    }

    @Test
    public void testRunHealingCycle_InsufficientHosts() {
        // Delete HostB and HostC container or mark them offline to leave only HostA
        hostB.setStatus(Host.Status.OFFLINE);
        hostRepository.save(hostB);
        hostC.setStatus(Host.Status.OFFLINE);
        hostRepository.save(hostC);

        // Only Host A is available. Healing should restore 1 replica and fail remaining 2
        RepairResultDto result = selfHealingService.runHealingCycle();

        assertEquals(1, result.getChunksInspected());
        assertEquals(3, result.getRepairsInitiated());
        assertEquals(1, result.getRepairsSucceeded());
        assertEquals(2, result.getRepairsFailed());
    }
}
