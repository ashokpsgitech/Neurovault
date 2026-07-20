package com.neurovault.backend.repository;

import com.neurovault.backend.entity.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for FileMetadata entity.
 */
@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {
    List<FileMetadata> findByOwnerId(UUID ownerId);
    Optional<FileMetadata> findByFileHash(String fileHash);
}
