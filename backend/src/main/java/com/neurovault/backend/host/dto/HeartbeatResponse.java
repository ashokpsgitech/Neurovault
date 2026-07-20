package com.neurovault.backend.host.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO returned by the coordinator after processing a heartbeat.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HeartbeatResponse {

    private boolean acknowledged;
    private Integer nextHeartbeatSeconds;
    private LocalDateTime serverTimestamp;
}
