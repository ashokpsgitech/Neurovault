package com.neurovault.backend.monitor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO encapsulating cluster-wide statistics for the analytics dashboard.
 *
 * <p>Computed by {@link com.neurovault.backend.monitor.service.ClusterAnalyticsService}
 * and cached with a short TTL to avoid excessive database queries.</p>
 *
 * @author NeuroVault Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClusterStatistics {

    private int totalHosts;
    private int onlineHosts;
    private int offlineHosts;

    private long totalStorageBytes;
    private long usedStorageBytes;
    private long availableStorageBytes;

    private long totalFiles;
    private long totalChunks;
    private long totalReplicas;
    private long activeReplicas;
    private long missingReplicas;
    private long corruptedReplicas;

    private long repairCount;
    private long recoveryCount;

    private double averageReplicationFactor;
    private double clusterUtilizationPercent;

    private LocalDateTime timestamp;
}
