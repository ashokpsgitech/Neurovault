package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Download plan returned by the Coordinator to the client.
 * Contains chunk locations across host data plane agents and the encrypted AES key envelope.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DownloadPlanResponse {

    private UUID downloadSessionId;
    private UUID fileId;
    private String filename;
    private Long fileSize;
    private String checksum;
    private String encryptedAesKey;
    private List<ChunkLocationDto> chunkLocations;
    private LocalDateTime expiresAt;
}
