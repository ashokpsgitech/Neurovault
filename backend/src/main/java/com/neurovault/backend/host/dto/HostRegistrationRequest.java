package com.neurovault.backend.host.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for host registration request sent by a Host Agent or Flutter Client
 * to register itself with the Metadata Coordinator.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostRegistrationRequest {

    @JsonAlias({"name", "hostname"})
    private String hostname;

    @JsonAlias({"deviceType", "deviceName"})
    private String deviceName;

    private String operatingSystem;

    private String architecture;

    @JsonAlias({"totalCapacityBytes", "availableStorageBytes"})
    private Long availableStorageBytes;

    private Long reservedStorageBytes;

    private String hostVersion;

    private Integer listeningPort;

    private String publicIp;

    private String localIp;

    public String getHostname() {
        return (hostname != null && !hostname.isBlank()) ? hostname : "MicroServer-Node";
    }

    public String getDeviceName() {
        return (deviceName != null && !deviceName.isBlank()) ? deviceName : "Desktop";
    }

    public String getOperatingSystem() {
        return (operatingSystem != null && !operatingSystem.isBlank()) ? operatingSystem : "Windows";
    }

    public String getArchitecture() {
        return (architecture != null && !architecture.isBlank()) ? architecture : "x86_64";
    }

    public String getHostVersion() {
        return (hostVersion != null && !hostVersion.isBlank()) ? hostVersion : "1.0.0";
    }

    public Integer getListeningPort() {
        return listeningPort != null ? listeningPort : 8080;
    }

    public Long getAvailableStorageBytes() {
        return availableStorageBytes != null ? availableStorageBytes : 53687091200L;
    }

    public Long getReservedStorageBytes() {
        return reservedStorageBytes != null ? reservedStorageBytes : 10737418240L;
    }
}
