package com.neurovault.backend.security;

import com.neurovault.backend.coordinator.CoordinatorService;
import com.neurovault.backend.dto.UploadCompleteRequest;
import com.neurovault.backend.dto.UploadPlanRequest;
import com.neurovault.backend.dto.UploadPlanResponse;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.host.controller.HostController;
import com.neurovault.backend.host.dto.HeartbeatRequest;
import com.neurovault.backend.repository.*;
import com.neurovault.backend.storage.controller.StorageController;
import com.neurovault.backend.storage.dto.CreateContainerRequest;
import com.neurovault.backend.storage.dto.StoreChunkRequest;
import com.neurovault.backend.storage.engine.StorageEngine;
import com.neurovault.backend.storage.exception.ChunkNotFoundException;
import com.neurovault.backend.storage.exception.CorruptedChunkException;
import com.neurovault.backend.storage.model.StorageReservationSize;
import com.neurovault.backend.upload.UploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:securitytestdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class SecurityAuthorizationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HostRepository hostRepository;

    @Autowired
    private StorageController storageController;

    @Autowired
    private HostController hostController;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private CoordinatorService coordinatorService;

    @Autowired
    private ChunkReplicaRepository chunkReplicaRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private UploadSessionRepository uploadSessionRepository;

    @Autowired
    private StorageContainerRepository containerRepository;

    private User userAlice;
    private User userBob;
    private Host hostAlice;
    private Host hostBob;

    @BeforeEach
    void setUp() {
        chunkReplicaRepository.deleteAll();
        chunkRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        containerRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        userAlice = userRepository.save(User.builder()
                .username("alice")
                .email("alice@test.com")
                .password("password123")
                .role(User.Role.CLIENT)
                .build());

        userBob = userRepository.save(User.builder()
                .username("bob")
                .email("bob@test.com")
                .password("password123")
                .role(User.Role.CLIENT)
                .build());

        hostAlice = hostRepository.save(Host.builder()
                .owner(userAlice)
                .name("alice-host")
                .deviceType("Desktop")
                .operatingSystem("Linux")
                .publicIp("127.0.0.1")
                .totalCapacityBytes(10000000000L)
                .reservedCapacityBytes(1000000000L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .heartbeatIntervalSeconds(30)
                .build());

        hostBob = hostRepository.save(Host.builder()
                .owner(userBob)
                .name("bob-host")
                .deviceType("Server")
                .operatingSystem("Linux")
                .publicIp("192.168.1.50")
                .totalCapacityBytes(20000000000L)
                .reservedCapacityBytes(1000000000L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .heartbeatIntervalSeconds(30)
                .build());
    }

    private Principal principalFor(User user) {
        return () -> user.getEmail();
    }

    @Test
    @DisplayName("IDOR Prevention: User Alice cannot access User Bob's host storage status")
    void testAliceCannotAccessBobHostStatus() {
        Principal alicePrincipal = principalFor(userAlice);

        assertThrows(AccessDeniedException.class, () -> {
            storageController.getStorageStatus(hostBob.getId(), alicePrincipal);
        });
    }

    @Test
    @DisplayName("Heartbeat Impersonation Prevention: User Alice cannot send heartbeats for User Bob's host")
    void testAliceCannotSendHeartbeatForBobHost() {
        Principal alicePrincipal = principalFor(userAlice);
        HeartbeatRequest request = HeartbeatRequest.builder()
                .hostId(hostBob.getId())
                .availableStorageBytes(20000000000L)
                .usedStorageBytes(500L)
                .build();

        assertThrows(AccessDeniedException.class, () -> {
            hostController.receiveHeartbeat(hostBob.getId(), request, alicePrincipal);
        });
    }

    @Test
    @DisplayName("Host Details Authorization: User Alice cannot inspect User Bob's host")
    void testAliceCannotViewBobHostDetails() {
        Principal alicePrincipal = principalFor(userAlice);

        assertThrows(AccessDeniedException.class, () -> {
            hostController.getHost(hostBob.getId(), alicePrincipal);
        });
    }

    @Test
    @DisplayName("Upload Session Isolation: User Bob cannot finalize User Alice's upload session")
    void testBobCannotFinalizeAliceUpload() {
        UploadPlanRequest planReq = UploadPlanRequest.builder()
                .filename("alice-secret.dat")
                .fileSize(4194304L)
                .totalChunks(1)
                .checksum("sha256checksum")
                .build();

        UploadPlanResponse plan = uploadService.createUploadPlan(planReq, userAlice);

        UploadCompleteRequest completeReq = UploadCompleteRequest.builder()
                .uploadSessionId(plan.getUploadSessionId())
                .encryptedAesKey("aliceEncryptedKey")
                .fileHash("sha256filehash")
                .uploadedChunks(List.of(
                        UploadCompleteRequest.UploadedChunkSummary.builder()
                                .chunkIndex(0)
                                .chunkId(UUID.randomUUID())
                                .chunkHash("chunkHash")
                                .sizeBytes(4194304L)
                                .hostId(hostAlice.getId())
                                .build()
                ))
                .build();

        assertThrows(AccessDeniedException.class, () -> {
            uploadService.completeUpload(completeReq, userBob);
        });
    }

    @Test
    @DisplayName("Upload Session Cancellation: User Bob cannot cancel User Alice's upload session")
    void testBobCannotCancelAliceUpload() {
        UploadPlanRequest planReq = UploadPlanRequest.builder()
                .filename("alice-cancel-test.dat")
                .fileSize(1024L)
                .totalChunks(1)
                .checksum("sha256")
                .build();

        UploadPlanResponse plan = uploadService.createUploadPlan(planReq, userAlice);

        assertThrows(AccessDeniedException.class, () -> {
            uploadService.cancelUpload(plan.getUploadSessionId(), userBob);
        });
    }

    @Test
    @DisplayName("Strict Chunk-Not-Found: Missing chunk throws ChunkNotFoundException, never returning first active chunk")
    void testMissingChunkThrowsNotFound() {
        Principal alicePrincipal = principalFor(userAlice);

        // Create Alice's container
        CreateContainerRequest createReq = CreateContainerRequest.builder()
                .hostId(hostAlice.getId())
                .reservationSize(StorageReservationSize.GB_1)
                .build();
        storageController.createStorage(createReq, alicePrincipal);

        // Store one active chunk
        UUID existingChunkId = UUID.randomUUID();
        StoreChunkRequest storeReq = StoreChunkRequest.builder()
                .chunkId(existingChunkId)
                .data(new byte[]{1, 2, 3, 4})
                .build();
        storageController.storeChunk(hostAlice.getId(), null, storeReq, alicePrincipal);

        // Query for a totally nonexistent chunk
        UUID nonexistentChunkId = UUID.randomUUID();
        assertThrows(ChunkNotFoundException.class, () -> {
            storageController.readChunk(nonexistentChunkId, hostAlice.getId(), null, alicePrincipal);
        });
    }

    @Test
    @DisplayName("Scoped Chunk Token: Authorized client can store and read chunk on foreign host with valid chunk token")
    void testScopedChunkTokenGrantsAccess() {
        Principal alicePrincipal = principalFor(userAlice);

        // Create Bob's container as Bob
        Principal bobPrincipal = principalFor(userBob);
        CreateContainerRequest createReq = CreateContainerRequest.builder()
                .hostId(hostBob.getId())
                .reservationSize(StorageReservationSize.GB_1)
                .build();
        storageController.createStorage(createReq, bobPrincipal);

        // Alice attempts to store chunk on Bob's host WITHOUT token -> fails with 403
        UUID chunkId = UUID.randomUUID();
        StoreChunkRequest storeReq = StoreChunkRequest.builder()
                .chunkId(chunkId)
                .data(new byte[]{10, 20, 30, 40})
                .build();

        assertThrows(AccessDeniedException.class, () -> {
            storageController.storeChunk(hostBob.getId(), null, storeReq, alicePrincipal);
        });

        // Now Coordinator generates valid WRITE chunk token for Bob's host
        UUID sessionId = UUID.randomUUID();
        String writeToken = coordinatorService.generateChunkToken(
                sessionId, hostBob.getId(), chunkId, 0,
                com.neurovault.backend.security.capability.CapabilityOperation.WRITE);

        // Alice stores chunk using the WRITE chunk token -> succeeds!
        var storedResponse = storageController.storeChunk(hostBob.getId(), writeToken, storeReq, alicePrincipal);
        assertNotNull(storedResponse);
        assertEquals(chunkId, storedResponse.getBody().getChunkId());

        // Attempting to read with the WRITE token is strictly rejected!
        assertThrows(AccessDeniedException.class, () -> {
            storageController.readChunk(chunkId, hostBob.getId(), writeToken, alicePrincipal);
        });

        // Generate distinct READ chunk token -> read succeeds!
        String readToken = coordinatorService.generateChunkToken(
                sessionId, hostBob.getId(), chunkId, 0,
                com.neurovault.backend.security.capability.CapabilityOperation.READ);

        var readResponse = storageController.readChunk(chunkId, hostBob.getId(), readToken, alicePrincipal);
        assertNotNull(readResponse);
        assertArrayEquals(new byte[]{10, 20, 30, 40}, readResponse.getBody());
    }
}
