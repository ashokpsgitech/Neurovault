package com.neurovault.backend.encryption;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * In-memory representation of a single file chunk with its data payload and metadata.
 * Used during the split/merge pipeline; does not correspond directly to a JPA entity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkData {

    /** Unique identifier for this chunk. */
    private UUID chunkId;

    /** Zero-based positional index of this chunk within the file. */
    private int chunkIndex;

    /** Size of this chunk's data in bytes. */
    private long chunkSize;

    /** SHA-256 hex digest of this chunk's data. */
    private String sha256Hash;

    /** CRC32 checksum of this chunk's data (decimal string). */
    private String checksum;

    /** ID of the user who owns the parent file. */
    private UUID ownerId;

    /** The raw chunk bytes (encrypted data). */
    private byte[] data;

    /** Current processing status of this chunk. */
    @Builder.Default
    private ChunkStatus status = ChunkStatus.PENDING;
}
