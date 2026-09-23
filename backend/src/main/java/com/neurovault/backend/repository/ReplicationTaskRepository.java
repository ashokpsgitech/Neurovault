package com.neurovault.backend.repository;

import com.neurovault.backend.entity.ReplicationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query("SELECT t FROM ReplicationTask t WHERE (t.status = 'PENDING' OR (t.status IN ('CLAIMED', 'RUNNING') AND t.leaseExpiresAt < :now)) AND t.attemptCount < t.maxAttempts ORDER BY t.createdAt ASC")
    List<ReplicationTask> findClaimableTasks(@Param("now") LocalDateTime now);

    /**
     * Atomically claims a replication task for a specific worker using a single database UPDATE query.
     * Prevents race conditions and multiple workers claiming the same task.
     * Returns 1 if claimed successfully, 0 if already claimed by another worker or expired.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReplicationTask t SET t.status = 'CLAIMED', t.workerId = :workerId, t.leaseExpiresAt = :leaseExpiresAt, t.attemptCount = t.attemptCount + 1, t.updatedAt = :now WHERE t.id = :taskId AND (t.status = 'PENDING' OR (t.status IN ('CLAIMED', 'RUNNING') AND t.leaseExpiresAt < :now)) AND t.attemptCount < t.maxAttempts")
    int claimTaskAtomic(@Param("taskId") UUID taskId,
                        @Param("workerId") String workerId,
                        @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                        @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReplicationTask t SET t.status = 'RUNNING', t.updatedAt = :now WHERE t.id = :taskId AND t.workerId = :workerId AND t.status = 'CLAIMED'")
    int markRunning(@Param("taskId") UUID taskId,
                    @Param("workerId") String workerId,
                    @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReplicationTask t SET t.status = 'SUCCEEDED', t.completedAt = :now, t.updatedAt = :now WHERE t.id = :taskId AND t.workerId = :workerId")
    int markSucceeded(@Param("taskId") UUID taskId,
                      @Param("workerId") String workerId,
                      @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReplicationTask t SET t.status = 'FAILED', t.lastError = :lastError, t.updatedAt = :now WHERE t.id = :taskId AND t.workerId = :workerId")
    int markFailed(@Param("taskId") UUID taskId,
                   @Param("workerId") String workerId,
                   @Param("lastError") String lastError,
                   @Param("now") LocalDateTime now);
}
