package com.neurovault.backend.upload;

import com.neurovault.backend.dto.UploadCompleteRequest;
import com.neurovault.backend.dto.UploadPlanRequest;
import com.neurovault.backend.dto.UploadPlanResponse;
import com.neurovault.backend.dto.UploadResponse;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for refactored Metadata-Only {@link UploadService}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uploadtestdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class UploadServiceTest {

    @Autowired
    private UploadService uploadService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HostRepository hostRepository;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private UploadSessionRepository uploadSessionRepository;

    @Autowired
    private ChunkReplicaRepository chunkReplicaRepository;

    private User testUser;
    private Host testHost;

    @BeforeEach
    void setUp() {
        chunkReplicaRepository.deleteAll();
        chunkRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("uploadtester")
                .email("upload@test.com")
                .password("password123")
                .role(User.Role.CLIENT)
                .build();
        testUser = userRepository.save(testUser);

        testHost = Host.builder()
                .owner(testUser)
                .name("test-host")
                .deviceType("Laptop")
                .operatingSystem("macOS")
                .publicIp("192.168.1.100")
                .totalCapacityBytes(5000000000L)
                .reservedCapacityBytes(1000000000L)
                .usedCapacityBytes(0L)
                .heartbeatIntervalSeconds(30)
                .status(Host.Status.ONLINE)
                .build();
        testHost = hostRepository.save(testHost);
    }

    @Test
    @DisplayName("createUploadPlan should generate target host allocations and tokens")
    void testCreateUploadPlan_Success() {
        UploadPlanRequest request = UploadPlanRequest.builder()
                .filename("plan-test.dat")
                .fileSize(10485760L)
                .totalChunks(3)
                .checksum("sha256checksum")
                .build();

        UploadPlanResponse plan = uploadService.createUploadPlan(request, testUser);

        assertNotNull(plan);
        assertNotNull(plan.getUploadSessionId());
        assertEquals("plan-test.dat", plan.getFilename());
        assertEquals(3, plan.getChunkAllocations().size());
        assertNotNull(plan.getChunkAllocations().get(0).getChunkToken());
    }

    @Test
    @DisplayName("completeUpload should finalize file metadata and chunk records")
    void testCompleteUpload_Success() {
        UploadPlanRequest planReq = UploadPlanRequest.builder()
                .filename("complete-test.dat")
                .fileSize(4194304L)
                .totalChunks(1)
                .checksum("sha256checksum")
                .build();

        UploadPlanResponse plan = uploadService.createUploadPlan(planReq, testUser);

        UploadCompleteRequest completeReq = UploadCompleteRequest.builder()
                .uploadSessionId(plan.getUploadSessionId())
                .encryptedAesKey("encryptedAesEnvelope")
                .uploadedChunks(List.of(
                        UploadCompleteRequest.UploadedChunkSummary.builder()
                                .chunkIndex(0)
                                .chunkId(UUID.randomUUID())
                                .chunkHash("chunk0hash")
                                .sizeBytes(4194304L)
                                .hostId(testHost.getId())
                                .build()
                ))
                .build();

        UploadResponse response = uploadService.completeUpload(completeReq, testUser);

        assertNotNull(response);
        assertEquals("COMPLETED", response.getStatus());
        assertTrue(fileMetadataRepository.findById(response.getFileId()).isPresent());
    }
}
