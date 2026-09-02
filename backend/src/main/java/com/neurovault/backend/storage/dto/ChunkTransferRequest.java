package com.neurovault.backend.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO for a chunk transfer command issued by the Coordinator to a source host.
 * The source host reads the chunk locally and sends it directly to the destination host.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkTransferRequest {

    /** ID of the chunk to transfer. */
    private UUID chunkId;

    /** ID of the source host (where the chunk currently resides). */
    private UUID sourceHostId;

    /** ID of the destination host (where the chunk should be replicated). */
    private UUID destinationHostId;

    /** Expected SHA-256 checksum for integrity verification. */
    private String expectedChecksum;

    /** Owner of the chunk data. */
    private UUID ownerId;
}
