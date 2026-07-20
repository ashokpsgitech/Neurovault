package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Upload plan response returned by the Coordinator to the client.
 * Contains assigned target host endpoints for direct chunk streaming.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadPlanResponse {

    private UUID uploadSessionId;
    private UUID fileId;
    private String filename;
    private Long fileSize;
    private Integer totalChunks;
    private List<ChunkAllocationDto> chunkAllocations;
    private LocalDateTime expiresAt;
}
