package com.neurovault.backend.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing the current storage status of a host's container.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageStatusResponse {

    private long containerSizeBytes;
    private long usedSpaceBytes;
    private long freeSpaceBytes;
    private int chunkCount;
    private String hostStatus;
    private String containerStatus;
}
