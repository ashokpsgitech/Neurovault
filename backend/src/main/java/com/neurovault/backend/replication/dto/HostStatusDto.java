package com.neurovault.backend.replication.dto;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.StorageContainer;
import com.neurovault.backend.monitor.model.HostHealthStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO representing the full status of a single host.
 *
 * <p>Returned as part of {@code GET /api/cluster/hosts}.</p>
 *
 * @author NeuroVault Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostStatusDto {

    /** Unique host identifier. */
    private UUID hostId;

    /** Human-readable host name. */
    private String name;

    /** Entity-level status (ONLINE / OFFLINE). */
    private Host.Status status;

    /** Detailed health classification from the monitor subsystem. */
    private HostHealthStatus healthStatus;

    /** Device type (e.g., LAPTOP, DESKTOP, MOBILE). */
    private String deviceType;

    /** Operating system of the host. */
    private String operatingSystem;

    /** Total storage capacity in bytes. */
    private long totalCapacityBytes;

    /** Currently used storage in bytes. */
    private long usedCapacityBytes;

    /** Reserved storage in bytes. */
    private long reservedCapacityBytes;

    /** Computed available storage in bytes. */
    private long availableCapacityBytes;

    /** Storage utilisation percentage. */
    private double usagePercent;

    /** Number of chunk replicas stored on this host. */
    private int replicaCount;

    /** Timestamp of the most recent heartbeat. */
    private LocalDateTime lastHeartbeat;

    /** Status of the host's storage container. */
    private StorageContainer.Status containerStatus;

    /** Most recent CPU usage percentage (from heartbeat). */
    private Double cpuUsagePercent;

    /** Most recent memory usage percentage (from heartbeat). */
    private Double memoryUsagePercent;

    /** List of human-readable issues detected for this host. */
    @Builder.Default
    private List<String> issues = List.of();
}
