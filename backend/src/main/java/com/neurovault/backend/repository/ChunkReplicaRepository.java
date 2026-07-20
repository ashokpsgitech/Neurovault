package com.neurovault.backend.repository;

import com.neurovault.backend.entity.ChunkReplica;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

/**
 * Repository interface for ChunkReplica entity.
 */
@Repository
public interface ChunkReplicaRepository extends JpaRepository<ChunkReplica, UUID> {
    List<ChunkReplica> findByChunkId(UUID chunkId);
    List<ChunkReplica> findByHostId(UUID hostId);
}
