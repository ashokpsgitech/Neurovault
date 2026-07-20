package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Target host allocation details for a specific chunk index.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkAllocationDto {

    private Integer chunkIndex;
    private UUID hostId;
    private String hostName;
    private String publicIp;
    private String uploadUrl;
    private String chunkToken;
    private Long maxSizeBytes;
}
