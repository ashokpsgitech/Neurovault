package com.neurovault.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing a durable replication/self-healing task in the cluster.
 */
@Entity
@Table(name = "replication_tasks", indexes = {
        @Index(name = "idx_repl_task_status", columnList = "status"),
        @Index(name = "idx_repl_task_chunk", columnList = "chunk_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplicationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chunk_id", nullable = false)
    private UUID chunkId;

    @Column(name = "source_host_id")
    private UUID sourceHostId;

    @Column(name = "target_host_id", nullable = false)
    private UUID targetHostId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 255)
    private String reason;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Builder.Default
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Builder.Default
    @Column(name = "backoff_seconds", nullable = false)
    private int backoffSeconds = 30;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = Status.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum Status {
        PENDING,
        CLAIMED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }
}
