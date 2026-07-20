package com.neurovault.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovault.backend.dto.UploadCompleteRequest;
import com.neurovault.backend.dto.UploadPlanRequest;
import com.neurovault.backend.dto.UploadPlanResponse;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.repository.*;
import com.neurovault.backend.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for refactored Metadata-Only {@link FileController} REST endpoints.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:filetestdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private Host testHost;
    private String jwtToken;

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
                .username("filetester")
                .email("filetest@example.com")
                .password(passwordEncoder.encode("password123"))
                .role(User.Role.CLIENT)
                .build();
        testUser = userRepository.save(testUser);

        testHost = Host.builder()
                .owner(testUser)
                .name("test-host-node")
                .deviceType("Desktop")
                .operatingSystem("Linux")
                .publicIp("127.0.0.1")
                .totalCapacityBytes(10737418240L)
                .reservedCapacityBytes(5368709120L)
                .usedCapacityBytes(0L)
                .heartbeatIntervalSeconds(30)
                .status(Host.Status.ONLINE)
                .build();
        testHost = hostRepository.save(testHost);

        jwtToken = jwtUtils.generateToken(testUser.getEmail());
    }

    @Test
    @DisplayName("POST /api/files/upload-plan should return target host allocations and tokens")
    void testRequestUploadPlan_Authenticated() throws Exception {
        UploadPlanRequest request = UploadPlanRequest.builder()
                .filename("test-file.dat")
                .fileSize(8388608L)
                .totalChunks(2)
                .checksum("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .build();

        mockMvc.perform(post("/api/files/upload-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadSessionId", notNullValue()))
                .andExpect(jsonPath("$.filename", is("test-file.dat")))
                .andExpect(jsonPath("$.totalChunks", is(2)))
                .andExpect(jsonPath("$.chunkAllocations", hasSize(2)))
                .andExpect(jsonPath("$.chunkAllocations[0].chunkToken", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/files/upload-plan should reject unauthenticated request")
    void testRequestUploadPlan_Unauthenticated() throws Exception {
        UploadPlanRequest request = UploadPlanRequest.builder()
                .filename("unauth.dat")
                .fileSize(1024L)
                .totalChunks(1)
                .checksum("dummy")
                .build();

        mockMvc.perform(post("/api/files/upload-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/files/upload-complete should finalize metadata")
    void testCompleteUpload() throws Exception {
        // Step 1: Request upload plan
        UploadPlanRequest planReq = UploadPlanRequest.builder()
                .filename("complete-test.dat")
                .fileSize(4194304L)
                .totalChunks(1)
                .checksum("dummyhash")
                .build();

        MvcResult planResult = mockMvc.perform(post("/api/files/upload-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(planReq))
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isCreated())
                .andReturn();

        UploadPlanResponse planResp = objectMapper.readValue(
                planResult.getResponse().getContentAsString(), UploadPlanResponse.class);

        // Step 2: Send completion notification
        UploadCompleteRequest completeReq = UploadCompleteRequest.builder()
                .uploadSessionId(planResp.getUploadSessionId())
                .encryptedAesKey("base64EncryptedAesKeyEnvelope")
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

        mockMvc.perform(post("/api/files/upload-complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeReq))
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId", is(planResp.getUploadSessionId().toString())))
                .andExpect(jsonPath("$.fileId", notNullValue()))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }
}
