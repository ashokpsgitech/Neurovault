package com.neurovault.backend.storage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO for storing an encrypted chunk in the container.
 * The data field contains raw encrypted bytes — no decryption occurs on the host.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoreChunkRequest {

    @NotNull(message = "Chunk ID is required")
    private UUID chunkId;

    @NotNull(message = "Owner ID is required")
    private UUID ownerId;

    @NotNull(message = "Chunk data is required")
    private byte[] data;
}
