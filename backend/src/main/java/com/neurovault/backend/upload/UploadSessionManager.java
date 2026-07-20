package com.neurovault.backend.upload;

import com.neurovault.backend.dto.UploadStatisticsResponse;
import com.neurovault.backend.entity.UploadSession;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.repository.UploadSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service managing the lifecycle of upload sessions.
 *
 * <p>Handles creation, state transitions, retrieval, and statistics
 * for upload sessions persisted via {@link UploadSessionRepository}.</p>
 */
@Service
@Slf4j
public class UploadSessionManager {

    private final UploadSessionRepository uploadSessionRepository;

    public UploadSessionManager(UploadSessionRepository uploadSessionRepository) {
        this.uploadSessionRepository = uploadSessionRepository;
    }

    /**
     * Creates a new upload session with INITIALIZED status.
     *
     * @param user        the user initiating the upload
     * @param fileName    the original file name
     * @param fileSize    the original file size in bytes
     * @param totalChunks the total number of chunks
     * @return the persisted {@link UploadSession}
     */
    @Transactional
    public UploadSession createSession(User user, String fileName, long fileSize, int totalChunks) {
        UploadSession session = UploadSession.builder()
                .user(user)
                .fileName(fileName)
                .fileSize(fileSize)
                .totalChunks(totalChunks)
                .status(UploadSession.Status.INITIALIZED)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();

        UploadSession saved = uploadSessionRepository.save(session);
        log.info("Created upload session {} for file '{}' ({} chunks)",
                saved.getId(), fileName, totalChunks);
        return saved;
    }

    /**
     * Updates the status of an existing upload session.
     *
     * @param sessionId the session ID
     * @param status    the new status
     * @return the updated session
     * @throws ResourceNotFoundException if the session does not exist
     */
    @Transactional
    public UploadSession updateStatus(UUID sessionId, UploadSession.Status status) {
        UploadSession session = getSession(sessionId);
        session.setStatus(status);
        UploadSession updated = uploadSessionRepository.save(session);
        log.info("Upload session {} status updated to {}", sessionId, status);
        return updated;
    }

    /**
     * Retrieves an upload session by ID.
     *
     * @param sessionId the session ID
     * @return the upload session
     * @throws ResourceNotFoundException if the session does not exist
     */
    public UploadSession getSession(UUID sessionId) {
        return uploadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Upload session not found: " + sessionId));
    }

    /**
     * Retrieves all upload sessions for a user.
     *
     * @param userId the user ID
     * @return list of upload sessions
     */
    public List<UploadSession> getUserSessions(UUID userId) {
        return uploadSessionRepository.findByUserId(userId);
    }

    /**
     * Computes aggregated upload statistics for a user.
     *
     * @param userId the user ID
     * @return upload statistics
     */
    public UploadStatisticsResponse getStatistics(UUID userId) {
        List<UploadSession> sessions = getUserSessions(userId);

        int total = sessions.size();
        int completed = (int) sessions.stream()
                .filter(s -> s.getStatus() == UploadSession.Status.COMPLETED).count();
        int failed = (int) sessions.stream()
                .filter(s -> s.getStatus() == UploadSession.Status.FAILED).count();
        int inProgress = (int) sessions.stream()
                .filter(s -> s.getStatus() == UploadSession.Status.UPLOADING
                        || s.getStatus() == UploadSession.Status.INITIALIZED).count();
        long totalBytes = sessions.stream()
                .filter(s -> s.getStatus() == UploadSession.Status.COMPLETED)
                .mapToLong(UploadSession::getFileSize)
                .sum();

        return UploadStatisticsResponse.builder()
                .totalUploads(total)
                .completedUploads(completed)
                .failedUploads(failed)
                .inProgressUploads(inProgress)
                .totalBytesUploaded(totalBytes)
                .build();
    }
}
