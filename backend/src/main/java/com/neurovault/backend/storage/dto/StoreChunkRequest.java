package com.neurovault.backend.storage.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
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

    private UUID ownerId;

    @NotNull(message = "Chunk data is required")
    private byte[] data;

    @JsonSetter("chunkId")
    public void setChunkIdFromObject(Object input) {
        if (input == null) return;
        if (input instanceof UUID) {
            this.chunkId = (UUID) input;
            return;
        }
        String str = input.toString().trim();
        try {
            this.chunkId = UUID.fromString(str);
        } catch (IllegalArgumentException e) {
            this.chunkId = UUID.nameUUIDFromBytes(str.getBytes());
        }
    }
}
