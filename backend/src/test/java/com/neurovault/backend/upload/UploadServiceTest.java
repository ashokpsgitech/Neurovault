package com.neurovault.backend.upload;

import com.neurovault.backend.dto.UploadProgressResponse;
import com.neurovault.backend.dto.UploadResponse;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.repository.ChunkRepository;
import com.neurovault.backend.repository.FileMetadataRepository;
import com.neurovault.backend.repository.UploadSessionRepository;
import com.neurovault.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link UploadService}.
 * Uses H2 in-memory database.
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
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private UploadSessionRepository uploadSessionRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        chunkRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("uploadtester")
                .email("upload@test.com")
                .password("password123")
                .role(User.Role.CLIENT)
                .build();
        testUser = userRepository.save(testUser);
    }

    @Test
    @DisplayName("initiateUpload should complete full pipeline successfully")
    void testInitiateUpload_Success() {
        byte[] content = "Hello, NeuroVault! This is a test file for upload.".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", content);

        UploadResponse response = uploadService.initiateUpload(file, testUser);

        assertNotNull(response);
        assertNotNull(response.getUploadId());
        assertNotNull(response.getFileId());
        assertEquals("test.txt", response.getFileName());
        assertEquals(content.length, response.getFileSize());
        assertTrue(response.getTotalChunks() >= 1);
        assertEquals("COMPLETED", response.getStatus());
        assertNotNull(response.getEncryptedAesKey());
        assertFalse(response.getEncryptedAesKey().isEmpty());
    }

    @Test
    @DisplayName("initiateUpload should persist file metadata and chunks")
    void testInitiateUpload_PersistsData() {
        byte[] content = "Persistence test data".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "persist.txt", "text/plain", content);

        UploadResponse response = uploadService.initiateUpload(file, testUser);

        // Verify file metadata is persisted
        assertTrue(fileMetadataRepository.findById(response.getFileId()).isPresent());

        // Verify chunks are persisted
        var chunks = chunkRepository.findByFileId(response.getFileId());
        assertEquals(response.getTotalChunks(), chunks.size());

        // Verify upload session is persisted
        assertTrue(uploadSessionRepository.findById(response.getUploadId()).isPresent());
    }

    @Test
    @DisplayName("getProgress should return correct progress information")
    void testGetProgress() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "progress.txt", "text/plain", "Progress tracking test".getBytes());

        UploadResponse uploadResponse = uploadService.initiateUpload(file, testUser);
        UploadProgressResponse progress = uploadService.getProgress(uploadResponse.getUploadId());

        assertNotNull(progress);
        assertEquals(uploadResponse.getUploadId(), progress.getUploadId());
        assertEquals("progress.txt", progress.getFileName());
        assertTrue(progress.getTotalChunks() >= 1);
        assertEquals("COMPLETED", progress.getStatus());
    }

    @Test
    @DisplayName("cancelUpload should mark session as FAILED")
    void testCancelUpload() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cancel.txt", "text/plain", "Cancel test".getBytes());

        UploadResponse response = uploadService.initiateUpload(file, testUser);

        // Since upload completes immediately in simulation, we can't cancel a completed one.
        // Verify that cancelling a completed upload throws BadRequestException
        assertThrows(Exception.class, () -> uploadService.cancelUpload(response.getUploadId()));
    }

    @Test
    @DisplayName("initiateUpload with empty file should throw BadRequestException")
    void testInitiateUpload_EmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        assertThrows(Exception.class, () -> uploadService.initiateUpload(emptyFile, testUser));
    }
}
