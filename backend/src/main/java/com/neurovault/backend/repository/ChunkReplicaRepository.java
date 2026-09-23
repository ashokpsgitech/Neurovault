package com.neurovault.backend.repository;

import com.neurovault.backend.entity.ChunkReplica;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    List<ChunkReplica> findByChunkIdAndStatus(UUID chunkId, ChunkReplica.Status status);

    List<ChunkReplica> findByHostIdAndStatus(UUID hostId, ChunkReplica.Status status);

    long countByChunkIdAndStatus(UUID chunkId, ChunkReplica.Status status);

    /**
     * Efficiently identifies all under-replicated chunks using a single aggregate SQL query
     * with GROUP BY and HAVING. Eliminates N+1 query performance bottleneck.
     *
     * @param targetFactor required replication factor
     * @return list of [chunkId (UUID), deficit (Long)]
     */
    @Query("SELECT c.id, (:targetFactor - COUNT(r.id)) " +
            "FROM Chunk c " +
            "LEFT JOIN ChunkReplica r ON r.chunk.id = c.id AND r.status = 'ACTIVE' " +
            "WHERE c.status = 'ACTIVE' " +
            "GROUP BY c.id " +
            "HAVING COUNT(r.id) < :targetFactor")
    List<Object[]> findUnderReplicatedChunksAggregate(@Param("targetFactor") long targetFactor);
}
