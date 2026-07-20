package com.neurovault.backend.host.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for host registration request sent by a Host Agent
 * to register itself with the Metadata Coordinator.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostRegistrationRequest {

    @NotBlank(message = "Hostname is required")
    @Size(max = 100, message = "Hostname must not exceed 100 characters")
    private String hostname;

    @NotBlank(message = "Device name is required")
    @Size(max = 100, message = "Device name must not exceed 100 characters")
    private String deviceName;

    @NotBlank(message = "Operating system is required")
    @Size(max = 50, message = "Operating system must not exceed 50 characters")
    private String operatingSystem;

    @NotBlank(message = "Architecture is required")
    @Size(max = 50, message = "Architecture must not exceed 50 characters")
    private String architecture;

    @NotNull(message = "Available storage is required")
    @Min(value = 0, message = "Available storage must be non-negative")
    private Long availableStorageBytes;

    @NotNull(message = "Reserved storage is required")
    @Min(value = 0, message = "Reserved storage must be non-negative")
    private Long reservedStorageBytes;

    @NotBlank(message = "Host version is required")
    @Size(max = 20, message = "Host version must not exceed 20 characters")
    private String hostVersion;

    @NotNull(message = "Listening port is required")
    @Min(value = 1, message = "Port must be at least 1")
    @Max(value = 65535, message = "Port must not exceed 65535")
    private Integer listeningPort;

    @Size(max = 45, message = "Public IP must not exceed 45 characters")
    private String publicIp;

    @Size(max = 45, message = "Local IP must not exceed 45 characters")
    private String localIp;
}
