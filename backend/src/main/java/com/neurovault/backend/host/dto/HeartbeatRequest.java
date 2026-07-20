package com.neurovault.backend.host.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for periodic heartbeat reports sent by a Host Agent.
 * Contains system health metrics and storage usage data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HeartbeatRequest {

    @NotNull(message = "Host ID is required")
    private UUID hostId;

    @NotNull(message = "Timestamp is required")
    private LocalDateTime timestamp;

    @Min(value = 0, message = "CPU usage must be non-negative")
    @Max(value = 100, message = "CPU usage must not exceed 100")
    private Double cpuUsagePercent;

    @Min(value = 0, message = "RAM usage must be non-negative")
    @Max(value = 100, message = "RAM usage must not exceed 100")
    private Double ramUsagePercent;

    @Min(value = 0, message = "Reserved storage must be non-negative")
    private Long reservedStorageBytes;

    @Min(value = 0, message = "Used storage must be non-negative")
    private Long usedStorageBytes;

    @Min(value = 0, message = "Available storage must be non-negative")
    private Long availableStorageBytes;

    @NotNull(message = "Host status is required")
    private String hostStatus;

    private String containerStatus;
}
