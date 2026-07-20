package com.neurovault.backend.controller;

import com.neurovault.backend.entity.User;
import com.neurovault.backend.repository.ChunkRepository;
import com.neurovault.backend.repository.DownloadSessionRepository;
import com.neurovault.backend.repository.FileMetadataRepository;
import com.neurovault.backend.repository.UploadSessionRepository;
import com.neurovault.backend.repository.UserRepository;
import com.neurovault.backend.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link FileController} REST endpoints.
 * Uses H2 in-memory database and authenticated JWT tokens.
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
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private UploadSessionRepository uploadSessionRepository;

    @Autowired
    private DownloadSessionRepository downloadSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    private User testUser;
    private String jwtToken;

    @BeforeEach
    void setUp() {
        downloadSessionRepository.deleteAll();
        chunkRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("filetester")
                .email("filetest@example.com")
                .password(passwordEncoder.encode("password123"))
                .role(User.Role.CLIENT)
                .build();
        testUser = userRepository.save(testUser);

        // Generate JWT token for the test user (subject is email)
        jwtToken = jwtUtils.generateToken(testUser.getEmail());
    }

    @Test
    @DisplayName("POST /api/files/upload should succeed with authenticated user")
    void testUploadFile_Authenticated() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test-upload.txt", "text/plain",
                "File content for controller test".getBytes());

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadId", notNullValue()))
                .andExpect(jsonPath("$.fileId", notNullValue()))
                .andExpect(jsonPath("$.fileName", is("test-upload.txt")))
                .andExpect(jsonPath("$.totalChunks", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.encryptedAesKey", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/files/upload should reject unauthenticated request")
    void testUploadFile_Unauthenticated() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "Content".getBytes());

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/files/progress/{uploadId} should return progress info")
    void testGetProgress() throws Exception {
        // First upload a file
        MockMultipartFile file = new MockMultipartFile(
                "file", "progress-test.txt", "text/plain",
                "Progress tracking content".getBytes());

        MvcResult uploadResult = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isCreated())
                .andReturn();

        // Extract uploadId from response
        String responseBody = uploadResult.getResponse().getContentAsString();
        String uploadId = com.jayway.jsonpath.JsonPath.read(responseBody, "$.uploadId");

        // Get progress
        mockMvc.perform(get("/api/files/progress/" + uploadId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId", is(uploadId)))
                .andExpect(jsonPath("$.fileName", is("progress-test.txt")))
                .andExpect(jsonPath("$.status", notNullValue()));
    }

    @Test
    @DisplayName("DELETE /api/files/cancel/{uploadId} on completed upload should return error")
    void testCancelUpload_CompletedUpload() throws Exception {
        // Upload a file (completes immediately)
        MockMultipartFile file = new MockMultipartFile(
                "file", "cancel-test.txt", "text/plain", "Cancel test".getBytes());

        MvcResult uploadResult = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isCreated())
                .andReturn();

        String uploadId = com.jayway.jsonpath.JsonPath.read(
                uploadResult.getResponse().getContentAsString(), "$.uploadId");

        // Try to cancel a completed upload — should fail
        mockMvc.perform(delete("/api/files/cancel/" + uploadId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/files/progress/{uploadId} with invalid ID should return 404")
    void testGetProgress_InvalidId() throws Exception {
        mockMvc.perform(get("/api/files/progress/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }
}
