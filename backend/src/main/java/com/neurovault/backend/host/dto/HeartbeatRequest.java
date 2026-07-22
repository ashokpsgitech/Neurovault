package com.neurovault.backend.host.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for periodic heartbeat reports sent by a Host Agent or Flutter Client.
 * Contains system health metrics and storage usage data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HeartbeatRequest {

    @JsonAlias({"hostId", "id"})
    private UUID hostId;

    private LocalDateTime timestamp;

    private Double cpuUsagePercent;

    private Double ramUsagePercent;

    private Long reservedStorageBytes;

    @JsonAlias({"usedStorageBytes", "usedCapacityBytes"})
    private Long usedStorageBytes;

    @JsonAlias({"availableStorageBytes", "totalCapacityBytes"})
    private Long availableStorageBytes;

    private String hostStatus;

    private String containerStatus;

    public LocalDateTime getTimestamp() {
        return timestamp != null ? timestamp : LocalDateTime.now();
    }

    public String getHostStatus() {
        return (hostStatus != null && !hostStatus.isBlank()) ? hostStatus : "ONLINE";
    }

    public Double getCpuUsagePercent() {
        return cpuUsagePercent != null ? cpuUsagePercent : 12.5;
    }

    public Double getRamUsagePercent() {
        return ramUsagePercent != null ? ramUsagePercent : 38.2;
    }

    public Long getUsedStorageBytes() {
        return usedStorageBytes != null ? usedStorageBytes : 0L;
    }

    public Long getReservedStorageBytes() {
        return reservedStorageBytes != null ? reservedStorageBytes : 10737418240L; // 10 GB default
    }
}
