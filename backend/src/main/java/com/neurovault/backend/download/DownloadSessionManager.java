package com.neurovault.backend.download;

import com.neurovault.backend.dto.DownloadStatisticsResponse;
import com.neurovault.backend.entity.DownloadSession;
import com.neurovault.backend.entity.FileMetadata;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.repository.DownloadSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service managing the lifecycle of download sessions.
 *
 * <p>Handles creation, state transitions, retrieval, and statistics
 * for download sessions persisted via {@link DownloadSessionRepository}.</p>
 */
@Service
@Slf4j
public class DownloadSessionManager {

    private final DownloadSessionRepository downloadSessionRepository;

    public DownloadSessionManager(DownloadSessionRepository downloadSessionRepository) {
        this.downloadSessionRepository = downloadSessionRepository;
    }

    /**
     * Creates a new download session with INITIALIZED status.
     *
     * @param user the user requesting the download
     * @param file the file metadata being downloaded
     * @return the persisted {@link DownloadSession}
     */
    @Transactional
    public DownloadSession createSession(User user, FileMetadata file) {
        DownloadSession session = DownloadSession.builder()
                .user(user)
                .file(file)
                .status(DownloadSession.Status.INITIALIZED)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();

        DownloadSession saved = downloadSessionRepository.save(session);
        log.info("Created download session {} for file '{}' by user {}",
                saved.getId(), file.getName(), user.getId());
        return saved;
    }

    /**
     * Updates the status of an existing download session.
     *
     * @param sessionId the session ID
     * @param status    the new status
     * @return the updated session
     * @throws ResourceNotFoundException if the session does not exist
     */
    @Transactional
    public DownloadSession updateStatus(UUID sessionId, DownloadSession.Status status) {
        DownloadSession session = getSession(sessionId);
        session.setStatus(status);
        DownloadSession updated = downloadSessionRepository.save(session);
        log.info("Download session {} status updated to {}", sessionId, status);
        return updated;
    }

    /**
     * Retrieves a download session by ID.
     *
     * @param sessionId the session ID
     * @return the download session
     * @throws ResourceNotFoundException if the session does not exist
     */
    public DownloadSession getSession(UUID sessionId) {
        return downloadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Download session not found: " + sessionId));
    }

    /**
     * Retrieves all download sessions for a user.
     *
     * @param userId the user ID
     * @return list of download sessions
     */
    public List<DownloadSession> getUserSessions(UUID userId) {
        return downloadSessionRepository.findByUserId(userId);
    }

    /**
     * Computes aggregated download statistics for a user.
     *
     * @param userId the user ID
     * @return download statistics
     */
    public DownloadStatisticsResponse getStatistics(UUID userId) {
        List<DownloadSession> sessions = getUserSessions(userId);

        int total = sessions.size();
        int completed = (int) sessions.stream()
                .filter(s -> s.getStatus() == DownloadSession.Status.COMPLETED).count();
        int failed = (int) sessions.stream()
                .filter(s -> s.getStatus() == DownloadSession.Status.FAILED).count();
        int inProgress = (int) sessions.stream()
                .filter(s -> s.getStatus() == DownloadSession.Status.DOWNLOADING
                        || s.getStatus() == DownloadSession.Status.INITIALIZED).count();

        return DownloadStatisticsResponse.builder()
                .totalDownloads(total)
                .completedDownloads(completed)
                .failedDownloads(failed)
                .inProgressDownloads(inProgress)
                .build();
    }
}
