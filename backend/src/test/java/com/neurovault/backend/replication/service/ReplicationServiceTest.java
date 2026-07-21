package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.*;
import com.neurovault.backend.replication.exception.ReplicationException;
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
        "spring.datasource.url=jdbc:h2:mem:testdb_rep;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Transactional
public class ReplicationServiceTest {

    @Autowired
    private ReplicationService replicationService;

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
    private Host hostA;
    private Host hostB;
    private Host hostC;
    private FileMetadata fileMetadata;
    private Chunk chunk;

    @BeforeEach
    public void setup() {
        chunkReplicaRepository.deleteAll();
        chunkRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("repuser")
                .email("rep@example.com")
                .password("password")
                .role(User.Role.CLIENT)
                .build();
        userRepository.save(testUser);

        hostA = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("HostA")
                .totalCapacityBytes(5000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        hostB = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("HostB")
                .totalCapacityBytes(5000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        hostC = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("HostC")
                .totalCapacityBytes(5000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        fileMetadata = fileMetadataRepository.save(FileMetadata.builder()
                .owner(testUser)
                .name("testfile.txt")
                .path("/path/testfile.txt")
                .sizeBytes(100L)
                .fileHash("mockhash")
                .encryptedAesKey("mockkey")
                .build());

        chunk = chunkRepository.save(Chunk.builder()
                .file(fileMetadata)
                .chunkIndex(0)
                .sizeBytes(100L)
                .checksum("mockchecksum")
                .status(Chunk.Status.ACTIVE)
                .build());
    }

    @Test
    public void testAssignReplicas_Success() {
        List<ChunkReplica> replicas = replicationService.assignReplicas(
                chunk.getId(), List.of(hostA.getId(), hostB.getId())
        );

        assertEquals(2, replicas.size());
        assertEquals(2, chunkReplicaRepository.findByChunkId(chunk.getId()).size());

        ChunkReplica r1 = replicas.get(0);
        assertEquals(chunk.getId(), r1.getChunk().getId());
        assertEquals(ChunkReplica.Status.ACTIVE, r1.getStatus());
    }

    @Test
    public void testAssignReplicas_DuplicatePrevention() {
        // Assign once
        replicationService.assignReplicas(chunk.getId(), List.of(hostA.getId()));
        // Assign again to same host
        replicationService.assignReplicas(chunk.getId(), List.of(hostA.getId()));

        List<ChunkReplica> replicas = chunkReplicaRepository.findByChunkId(chunk.getId());
        assertEquals(1, replicas.size(), "Should prevent duplicate replicas of same chunk on same host");
    }

    @Test
    public void testRemoveReplica_Success() {
        List<ChunkReplica> replicas = replicationService.assignReplicas(chunk.getId(), List.of(hostA.getId()));
        UUID replicaId = replicas.get(0).getId();

        replicationService.removeReplica(replicaId);
        assertTrue(chunkReplicaRepository.findById(replicaId).isEmpty());
    }

    @Test
    public void testUpdateReplicaStatus() {
        List<ChunkReplica> replicas = replicationService.assignReplicas(chunk.getId(), List.of(hostA.getId()));
        UUID replicaId = replicas.get(0).getId();

        replicationService.updateReplicaStatus(replicaId, ChunkReplica.Status.CORRUPTED);

        ChunkReplica updated = chunkReplicaRepository.findById(replicaId).orElseThrow();
        assertEquals(ChunkReplica.Status.CORRUPTED, updated.getStatus());
    }

    @Test
    public void testVerifyReplicaCount_And_UnderReplicatedChunks() {
        // Target is 3 replicas (default factor)
        // With 0 replicas: deficit = 3
        assertEquals(3, replicationService.verifyReplicaCount(chunk.getId()));

        Map<UUID, Integer> underReplicated = replicationService.getUnderReplicatedChunks();
        assertEquals(1, underReplicated.size());
        assertEquals(3, underReplicated.get(chunk.getId()));

        // Assign 2 replicas
        replicationService.assignReplicas(chunk.getId(), List.of(hostA.getId(), hostB.getId()));

        // Deficit should now be 1
        assertEquals(1, replicationService.verifyReplicaCount(chunk.getId()));
        Map<UUID, Integer> underReplicatedAfter = replicationService.getUnderReplicatedChunks();
        assertEquals(1, underReplicatedAfter.size());
        assertEquals(1, underReplicatedAfter.get(chunk.getId()));

        // Assign 3rd replica
        replicationService.assignReplicas(chunk.getId(), List.of(hostC.getId()));

        // Deficit should be 0
        assertEquals(0, replicationService.verifyReplicaCount(chunk.getId()));
        assertTrue(replicationService.getUnderReplicatedChunks().isEmpty());
    }
}
