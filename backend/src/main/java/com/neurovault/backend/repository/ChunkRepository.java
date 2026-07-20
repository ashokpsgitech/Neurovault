package com.neurovault.backend.repository;

import com.neurovault.backend.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Chunk entity.
 */
@Repository
public interface ChunkRepository extends JpaRepository<Chunk, UUID> {
    List<Chunk> findByFileId(UUID fileId);
    Optional<Chunk> findByFileIdAndChunkIndex(UUID fileId, Integer chunkIndex);
}
