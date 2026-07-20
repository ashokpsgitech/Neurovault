package com.neurovault.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata payload sent by the client to request an upload plan from the Coordinator.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadPlanRequest {

    @NotBlank(message = "Filename is required")
    private String filename;

    @NotNull(message = "File size is required")
    @Min(value = 1, message = "File size must be greater than 0")
    private Long fileSize;

    @NotNull(message = "Total chunks count is required")
    @Min(value = 1, message = "Total chunks must be at least 1")
    private Integer totalChunks;

    private String mimeType;

    @NotBlank(message = "File SHA-256 checksum is required")
    private String checksum;
}
