package com.neurovault.backend.replication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for {@code GET /api/cluster/status}.
 *
 * <p>Provides a comprehensive summary of the cluster's current state, including
 * host counts, storage utilisation, file/chunk/replica totals, and repair metrics.</p>
 *
 * @author NeuroVault Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClusterStatusResponse {

    /** Total number of registered hosts. */
    private int totalHosts;

    /** Number of hosts currently online. */
    private int onlineHosts;

    /** Number of hosts currently offline or unreachable. */
    private int offlineHosts;

    /** Total storage capacity across all hosts (bytes). */
    private long totalStorageBytes;

    /** Total used storage across all hosts (bytes). */
    private long usedStorageBytes;

    /** Total available storage across all hosts (bytes). */
    private long availableStorageBytes;

    /** Number of files tracked by the coordinator. */
    private long totalFiles;

    /** Total number of chunks across all files. */
    private long totalChunks;

    /** Total number of chunk replicas across all hosts. */
    private long totalReplicas;

    /** Number of replicas in ACTIVE state. */
    private long activeReplicas;

    /** Number of replicas in MISSING state. */
    private long missingReplicas;

    /** Number of replicas in CORRUPTED state. */
    private long corruptedReplicas;

    /** Cumulative number of repairs initiated since last restart. */
    private long repairCount;

    /** Cumulative number of successful recoveries since last restart. */
    private long recoveryCount;

    /** Average replication factor across all chunks (totalActiveReplicas / totalChunks). */
    private double averageReplicationFactor;

    /** Cluster-wide storage utilisation percentage. */
    private double clusterUtilizationPercent;

    /** Timestamp when these statistics were computed. */
    private LocalDateTime timestamp;
}
