package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.*;
import com.neurovault.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_loadbalancing;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Transactional
public class LoadBalancingServiceTest {

    @Autowired
    private LoadBalancingService loadBalancingService;

    @Autowired
    private ReplicationService replicationService;

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
    private Chunk chunk1;
    private Chunk chunk2;

    @BeforeEach
    public void setup() {
        chunkReplicaRepository.deleteAll();
        chunkRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        containerRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("lbuser")
                .email("lb@example.com")
                .password("password")
                .role(User.Role.CLIENT)
                .build();
        userRepository.save(testUser);

        hostA = createHealthyHost("HostA");
        hostB = createHealthyHost("HostB");
        hostC = createHealthyHost("HostC");

        FileMetadata file = fileMetadataRepository.save(FileMetadata.builder()
                .owner(testUser)
                .name("lb_test.txt")
                .path("/path/lb_test.txt")
                .sizeBytes(200L)
                .fileHash("hash1")
                .encryptedAesKey("key1")
                .build());

        chunk1 = chunkRepository.save(Chunk.builder()
                .file(file)
                .chunkIndex(0)
                .sizeBytes(100L)
                .checksum("chk1")
                .status(Chunk.Status.ACTIVE)
                .build());

        chunk2 = chunkRepository.save(Chunk.builder()
                .file(file)
                .chunkIndex(1)
                .sizeBytes(100L)
                .checksum("chk2")
                .status(Chunk.Status.ACTIVE)
                .build());
    }

    private Host createHealthyHost(String name) {
        Host host = hostRepository.save(Host.builder()
                .owner(testUser)
                .name(name)
                .totalCapacityBytes(10000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        containerRepository.save(StorageContainer.builder()
                .host(host)
                .filePath("/tmp/" + name)
                .totalSize(10000L)
                .status(StorageContainer.Status.ACTIVE)
                .build());

        return host;
    }

    @Test
    public void testAnalyzeDistribution_BalancedVsImbalanced() {
        // Balanced: 1 replica each on all three hosts
        replicationService.assignReplicas(chunk1.getId(), List.of(hostA.getId(), hostB.getId(), hostC.getId()));

        assertFalse(loadBalancingService.analyzeDistribution(), "Perfectly balanced cluster shouldn't need rebalancing");

        // Imbalanced: Host A holds 2, Host B holds 0, Host C holds 0
        chunkReplicaRepository.deleteAll();
        replicationService.assignReplicas(chunk1.getId(), List.of(hostA.getId()));
        replicationService.assignReplicas(chunk2.getId(), List.of(hostA.getId()));

        assertTrue(loadBalancingService.analyzeDistribution(), "Highly imbalanced cluster should prompt rebalancing");
    }

    @Test
    public void testRebalanceCluster_SuccessfulMigration() {
        // Overload Host A: store both chunk1 and chunk2 replicas on Host A
        replicationService.assignReplicas(chunk1.getId(), List.of(hostA.getId()));
        replicationService.assignReplicas(chunk2.getId(), List.of(hostA.getId()));

        // Underloaded: Host B has 0, Host C has 0
        int migrated = loadBalancingService.rebalanceCluster();
        assertEquals(1, migrated, "Should migrate 1 replica to balance the load");

        Map<UUID, Integer> distribution = loadBalancingService.getLoadDistribution();
        assertEquals(1, distribution.get(hostA.getId()));
        // The migrated one must be on Host B or Host C
        assertTrue(distribution.get(hostB.getId()) == 1 || distribution.get(hostC.getId()) == 1);
    }
}
