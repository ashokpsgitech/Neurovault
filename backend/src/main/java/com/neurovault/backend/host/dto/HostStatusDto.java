package com.neurovault.backend.host.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO representing the current status and metadata of a registered host.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostStatusDto {

    @JsonProperty("id")
    @JsonAlias({"hostId", "id"})
    private UUID hostId;

    @JsonProperty("name")
    @JsonAlias({"hostname", "name"})
    private String hostname;

    @JsonProperty("deviceType")
    @JsonAlias({"deviceName", "deviceType"})
    private String deviceName;

    private String operatingSystem;
    private String architecture;
    private String status;
    private LocalDateTime lastHeartbeat;
    private Long totalCapacityBytes;
    private Long reservedCapacityBytes;
    private Long usedCapacityBytes;
    private Integer heartbeatIntervalSeconds;
    private LocalDateTime createdAt;
}
