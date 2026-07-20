package com.neurovault.backend.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO exposing chunk metadata through the REST API.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkMetadataDto {

    private UUID chunkId;
    private long chunkSize;
    private long offset;
    private Instant creationTime;
    private String sha256Hash;
    private long checksum;
    private UUID ownerId;
}
