package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.*;
import com.neurovault.backend.repository.*;
import com.neurovault.backend.storage.container.ContainerManager;
import com.neurovault.backend.storage.engine.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
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
public class LoadBalancingServiceTest {

    @TempDir
    Path tempDir;

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

    @Autowired
    private ContainerManager containerManager;

    @Autowired
    private StorageEngine storageEngine;

    private User testUser;
    private Host hostA;
    private Host hostB;
    private Host hostC;
    private Chunk chunk1;
    private Chunk chunk2;

    @BeforeEach
    public void setup() {
        chunkReplicaRepository.deleteAll();
        chunkReplicaRepository.flush();
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

    @AfterEach
    public void teardown() {
        if (hostA != null) containerManager.closeContainer(hostA.getId());
        if (hostB != null) containerManager.closeContainer(hostB.getId());
        if (hostC != null) containerManager.closeContainer(hostC.getId());
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

        Path containerPath = tempDir.resolve("container-" + host.getId() + ".container");
        containerManager.createContainer(host.getId(), containerPath, 10 * 1024 * 1024L);
        storageEngine.initialize(host.getId());

        containerRepository.save(StorageContainer.builder()
                .host(host)
                .filePath(containerPath.toString())
                .totalSize(10000L)
                .status(StorageContainer.Status.ACTIVE)
                .build());

        return host;
    }

    private ChunkReplica createActiveReplica(Chunk chunk, Host host) throws Exception {
        byte[] data = ("Payload-for-chunk-" + chunk.getId()).getBytes(StandardCharsets.UTF_8);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String hash = HexFormat.of().formatHex(md.digest(data));
        chunk.setChecksum(hash);
        chunk.setSizeBytes((long) data.length);
        chunkRepository.save(chunk);

        storageEngine.storeChunkStream(host.getId(), chunk.getId(), testUser.getId(),
                new ByteArrayInputStream(data), data.length, hash);

        return chunkReplicaRepository.save(ChunkReplica.builder()
                .chunk(chunk)
                .host(host)
                .containerOffsetBytes(0L)
                .status(ChunkReplica.Status.ACTIVE)
                .build());
    }

    private ChunkReplica createActiveReplicaDbOnly(Chunk chunk, Host host) {
        return chunkReplicaRepository.save(ChunkReplica.builder()
                .chunk(chunk)
                .host(host)
                .containerOffsetBytes(0L)
                .status(ChunkReplica.Status.ACTIVE)
                .build());
    }

    @Test
    public void testAnalyzeDistribution_BalancedVsImbalanced() {
        createActiveReplicaDbOnly(chunk1, hostA);
        createActiveReplicaDbOnly(chunk1, hostB);
        createActiveReplicaDbOnly(chunk1, hostC);

        assertFalse(loadBalancingService.analyzeDistribution(), "Perfectly balanced cluster shouldn't need rebalancing");

        chunkReplicaRepository.deleteAll();
        chunkReplicaRepository.flush();
        createActiveReplicaDbOnly(chunk1, hostA);
        createActiveReplicaDbOnly(chunk2, hostA);

        assertTrue(loadBalancingService.analyzeDistribution(), "Highly imbalanced cluster should prompt rebalancing");
    }

    @Test
    public void testRebalanceCluster_SuccessfulMigration() throws Exception {
        createActiveReplica(chunk1, hostA);
        createActiveReplica(chunk2, hostA);

        int migrated = loadBalancingService.rebalanceCluster();
        assertEquals(1, migrated, "Should migrate 1 replica to balance the load");

        Map<UUID, Integer> distribution = loadBalancingService.getLoadDistribution();
        assertEquals(1, distribution.get(hostA.getId()));
        assertTrue(distribution.get(hostB.getId()) == 1 || distribution.get(hostC.getId()) == 1);
    }
}
