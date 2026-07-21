package com.neurovault.backend.replication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for {@code GET /api/cluster/health}.
 *
 * <p>Provides the overall health assessment of the cluster along with any
 * active issues that require attention.</p>
 *
 * @author NeuroVault Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClusterHealthResponse {

    /**
     * Overall cluster health classification.
     */
    public enum HealthLevel {
        /** All hosts online, no issues detected. */
        HEALTHY,
        /** Some hosts offline or low storage, but no data-loss risk. */
        DEGRADED,
        /** Under-replicated chunks exist or majority of hosts are offline. */
        CRITICAL
    }

    /** The current health level of the cluster. */
    private HealthLevel healthLevel;

    /** Human-readable issues currently affecting the cluster. */
    @Builder.Default
    private List<String> issues = List.of();

    /** Number of hosts in a healthy state. */
    private int healthyHostCount;

    /** Number of hosts experiencing issues. */
    private int unhealthyHostCount;

    /** Number of chunks below the target replication factor. */
    private long underReplicatedChunks;

    /** Timestamp of the health evaluation. */
    private LocalDateTime timestamp;
}
