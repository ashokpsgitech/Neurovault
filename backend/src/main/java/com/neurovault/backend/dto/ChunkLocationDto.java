package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Host location details for fetching a specific chunk replica during download.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkLocationDto {

    private UUID chunkId;
    private Integer chunkIndex;
    private String chunkHash;
    private Long sizeBytes;
    private UUID hostId;
    private String hostName;
    private String publicIp;
    private String downloadUrl;
    private String downloadToken;
}
