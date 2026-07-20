package com.neurovault.backend.host.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO returned by the coordinator after a successful host registration.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostRegistrationResponse {

    private UUID hostId;
    private String registrationStatus;
    private Integer heartbeatIntervalSeconds;
    private LocalDateTime registeredAt;
}
