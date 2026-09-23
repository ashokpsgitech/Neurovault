package com.neurovault.backend.repository;

import com.neurovault.backend.entity.ReplicationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReplicationTaskRepository extends JpaRepository<ReplicationTask, UUID> {

    List<ReplicationTask> findByStatus(ReplicationTask.Status status);

    List<ReplicationTask> findByChunkId(UUID chunkId);

    @Query("SELECT t FROM ReplicationTask t WHERE t.status = 'PENDING' OR (t.status IN ('CLAIMED', 'RUNNING') AND t.leaseExpiresAt < :now)")
    List<ReplicationTask> findClaimableTasks(@Param("now") LocalDateTime now);
}
