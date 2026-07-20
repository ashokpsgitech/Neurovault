package com.neurovault.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Completion notification sent by the client after all encrypted chunks have been uploaded directly to hosts.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadCompleteRequest {

    @NotNull(message = "Upload session ID is required")
    private UUID uploadSessionId;

    @NotBlank(message = "Encrypted AES key is required")
    private String encryptedAesKey;

    @NotEmpty(message = "Uploaded chunks list cannot be empty")
    private List<UploadedChunkSummary> uploadedChunks;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UploadedChunkSummary {
        private Integer chunkIndex;
        private UUID chunkId;
        private String chunkHash;
        private Long sizeBytes;
        private UUID hostId;
    }
}
