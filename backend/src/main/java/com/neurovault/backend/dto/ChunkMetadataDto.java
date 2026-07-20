package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO representing metadata of a single file chunk for API responses.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkMetadataDto {

    /** Unique chunk identifier. */
    private UUID chunkId;

    /** Zero-based positional index of this chunk within the file. */
    private int chunkIndex;

    /** Size of this chunk in bytes. */
    private long sizeBytes;

    /** SHA-256 hex digest of this chunk's data. */
    private String sha256Hash;

    /** CRC32 checksum of this chunk's data. */
    private String checksum;

    /** Current status of this chunk. */
    private String status;
}
