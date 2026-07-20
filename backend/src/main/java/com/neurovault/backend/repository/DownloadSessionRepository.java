package com.neurovault.backend.repository;

import com.neurovault.backend.entity.DownloadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

/**
 * Repository interface for DownloadSession entity.
 */
@Repository
public interface DownloadSessionRepository extends JpaRepository<DownloadSession, UUID> {
    List<DownloadSession> findByUserId(UUID userId);
}
