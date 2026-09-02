package com.neurovault.backend.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO returned after a chunk transfer operation completes.
 * Reports success/failure and the actual checksum computed at the destination.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkTransferResponse {

    /** Whether the transfer completed successfully. */
    private boolean success;

    /** ID of the transferred chunk. */
    private UUID chunkId;

    /** ID of the source host. */
    private UUID sourceHostId;

    /** ID of the destination host. */
    private UUID destinationHostId;

    /** SHA-256 checksum computed at the destination after storage. */
    private String destinationChecksum;

    /** Error message if the transfer failed. */
    private String errorMessage;
}
