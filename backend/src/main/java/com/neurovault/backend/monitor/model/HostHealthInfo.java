package com.neurovault.backend.monitor.model;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.StorageContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Immutable record capturing the full health snapshot of a single host.
 *
 * <p>Produced by the {@link com.neurovault.backend.monitor.service.ClusterHealthMonitor}
 * after evaluating heartbeat, capacity, and container data.</p>
 *
 * @param hostId            Unique identifier of the host
 * @param hostName          Human-readable host name
 * @param status            Entity-level status (ONLINE / OFFLINE)
 * @param healthStatus      Detailed health classification
 * @param cpuUsage          Latest CPU usage from heartbeat (nullable)
 * @param memoryUsage       Latest memory usage from heartbeat (nullable)
 * @param totalCapacity     Total storage capacity in bytes
 * @param usedCapacity      Currently used storage in bytes
 * @param availableCapacity Computed available capacity in bytes
 * @param usagePercent      Storage utilisation as a percentage
 * @param replicaCount      Number of chunk replicas on this host
 * @param lastHeartbeat     Timestamp of the most recent heartbeat
 * @param containerStatus   Status of the storage container (nullable if no container)
 * @param issues            Human-readable list of detected problems
 *
 * @author NeuroVault Team
 */
public record HostHealthInfo(
        UUID hostId,
        String hostName,
        Host.Status status,
        HostHealthStatus healthStatus,
        Double cpuUsage,
        Double memoryUsage,
        long totalCapacity,
        long usedCapacity,
        long availableCapacity,
        double usagePercent,
        int replicaCount,
        LocalDateTime lastHeartbeat,
        StorageContainer.Status containerStatus,
        List<String> issues
) {
}
