package com.neurovault.backend.download;

import com.neurovault.backend.dto.DownloadPlanResponse;
import com.neurovault.backend.dto.UploadCompleteRequest;
import com.neurovault.backend.dto.UploadPlanRequest;
import com.neurovault.backend.dto.UploadPlanResponse;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.repository.*;
import com.neurovault.backend.upload.UploadService;
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
 * Integration tests for refactored Metadata-Only {@link DownloadService}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:downloadtestdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class DownloadServiceTest {

    @Autowired
    private DownloadService downloadService;

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
    private DownloadSessionRepository downloadSessionRepository;

    @Autowired
    private ChunkReplicaRepository chunkReplicaRepository;

    private User testUser;
    private Host testHost;

    @BeforeEach
    void setUp() {
        chunkReplicaRepository.deleteAll();
        downloadSessionRepository.deleteAll();
        chunkRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("downloadtester")
                .email("download@test.com")
                .password("password123")
                .role(User.Role.CLIENT)
                .build();
        testUser = userRepository.save(testUser);

        testHost = Host.builder()
                .owner(testUser)
                .name("test-host")
                .deviceType("Desktop")
                .operatingSystem("Linux")
                .publicIp("127.0.0.1")
                .totalCapacityBytes(5000000000L)
                .reservedCapacityBytes(1000000000L)
                .usedCapacityBytes(0L)
                .heartbeatIntervalSeconds(30)
                .status(Host.Status.ONLINE)
                .build();
        testHost = hostRepository.save(testHost);
    }

    @Test
    @DisplayName("createDownloadPlan should return chunk locations and tokens for metadata file")
    void testCreateDownloadPlan_Success() {
        // Step 1: Upload metadata
        UploadPlanRequest planReq = UploadPlanRequest.builder()
                .filename("dl-test.txt")
                .fileSize(4194304L)
                .totalChunks(1)
                .checksum("sha256checksum")
                .build();

        UploadPlanResponse planResp = uploadService.createUploadPlan(planReq, testUser);

        UploadCompleteRequest completeReq = UploadCompleteRequest.builder()
                .uploadSessionId(planResp.getUploadSessionId())
                .encryptedAesKey("encryptedAesEnvelopeKey")
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

        var uploadResult = uploadService.completeUpload(completeReq, testUser);

        // Step 2: Request download plan
        DownloadPlanResponse dlPlan = downloadService.createDownloadPlan(uploadResult.getFileId(), testUser);

        assertNotNull(dlPlan);
        assertEquals(uploadResult.getFileId(), dlPlan.getFileId());
        assertEquals("dl-test.txt", dlPlan.getFilename());
        assertEquals("encryptedAesEnvelopeKey", dlPlan.getEncryptedAesKey());
        assertEquals(1, dlPlan.getChunkLocations().size());
        assertNotNull(dlPlan.getChunkLocations().get(0).getDownloadToken());
    }
}
