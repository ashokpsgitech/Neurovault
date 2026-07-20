package com.neurovault.backend.repository;

import com.neurovault.backend.entity.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

/**
 * Repository interface for UploadSession entity.
 */
@Repository
public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {
    List<UploadSession> findByUserId(UUID userId);
}
