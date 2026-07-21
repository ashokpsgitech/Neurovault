package com.neurovault.backend.monitor.service;

import com.neurovault.backend.entity.*;
import com.neurovault.backend.monitor.dto.ClusterStatistics;
import com.neurovault.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_analytics;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Transactional
public class ClusterAnalyticsServiceTest {

    @Autowired
    private ClusterAnalyticsService analyticsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HostRepository hostRepository;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ChunkReplicaRepository chunkReplicaRepository;

    private User testUser;

    @BeforeEach
    public void setup() {
        chunkReplicaRepository.deleteAll();
        chunkRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("analyticuser")
                .email("an@example.com")
                .password("password")
                .role(User.Role.CLIENT)
                .build();
        userRepository.save(testUser);
    }

    @Test
    public void testComputeStatistics_Calculations() {
        // Register 2 hosts
        Host host1 = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("Host1")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(100L)
                .usedCapacityBytes(200L) // Available = 700 bytes
                .status(Host.Status.ONLINE)
                .build());

        Host host2 = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("Host2")
                .totalCapacityBytes(2000L)
                .reservedCapacityBytes(200L)
                .usedCapacityBytes(300L) // Available = 1500 bytes
                .status(Host.Status.OFFLINE)
                .build());

        // Create 1 File with 1 Chunk and 1 Replica
        FileMetadata file = fileMetadataRepository.save(FileMetadata.builder()
                .owner(testUser)
                .name("a.txt")
                .path("/path/a.txt")
                .sizeBytes(100L)
                .fileHash("hash")
                .encryptedAesKey("key")
                .build());

        Chunk chunk = chunkRepository.save(Chunk.builder()
                .file(file)
                .chunkIndex(0)
                .sizeBytes(100L)
                .checksum("chk")
                .status(Chunk.Status.ACTIVE)
                .build());

        chunkReplicaRepository.save(ChunkReplica.builder()
                .chunk(chunk)
                .host(host1)
                .containerOffsetBytes(0L)
                .status(ChunkReplica.Status.ACTIVE)
                .build());

        // Increment repair and recovery counts
        analyticsService.incrementRepairCount();
        analyticsService.incrementRecoveryCount();

        ClusterStatistics stats = analyticsService.forceRefresh();

        assertEquals(2, stats.getTotalHosts());
        assertEquals(1, stats.getOnlineHosts());
        assertEquals(1, stats.getOfflineHosts());

        assertEquals(3000L, stats.getTotalStorageBytes());
        assertEquals(500L, stats.getUsedStorageBytes());
        assertEquals(2200L, stats.getAvailableStorageBytes()); // available: 700 + 1500 = 2200

        assertEquals(1, stats.getTotalFiles());
        assertEquals(1, stats.getTotalChunks());
        assertEquals(1, stats.getTotalReplicas());
        assertEquals(1, stats.getActiveReplicas());

        assertEquals(1L, stats.getRepairCount());
        assertEquals(1L, stats.getRecoveryCount());

        assertEquals(1.0, stats.getAverageReplicationFactor());
        // 500 / 3000 * 100
        assertEquals(500.0 / 3000.0 * 100.0, stats.getClusterUtilizationPercent(), 0.001);
    }
}
