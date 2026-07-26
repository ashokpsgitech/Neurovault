package com.neurovault.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.neurovault.backend.exception.BadRequestException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata payload sent by the client to request an upload plan from the Coordinator.
 * Supports JsonAlias property names from both Flutter Client and REST endpoints.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadPlanRequest {

    @NotBlank(message = "filename is required")
    @JsonAlias({"filename", "name"})
    private String filename;

    @NotNull(message = "fileSize is required")
    @Min(value = 1, message = "fileSize must be at least 1 byte")
    @JsonAlias({"fileSize", "sizeBytes", "size"})
    private Long fileSize;

    @NotNull(message = "totalChunks is required")
    @Min(value = 1, message = "totalChunks must be at least 1")
    @JsonAlias({"totalChunks", "chunkCount", "chunks"})
    private Integer totalChunks;

    private String mimeType;

    @NotBlank(message = "checksum is required")
    @JsonAlias({"checksum", "sha256Checksum", "fileChecksum"})
    private String checksum;

    public String getFilename() {
        return (filename != null && !filename.isBlank()) ? filename : "neurovault_upload.bin";
    }

    public Long getFileSize() {
        return fileSize != null ? fileSize : 1024L;
    }

    public Integer getTotalChunks() {
        return (totalChunks != null && totalChunks > 0) ? totalChunks : 1;
    }

    public String getChecksum() {
        if (checksum == null || checksum.isBlank()) {
            throw new BadRequestException("File checksum (sha256) is required");
        }
        return checksum;
    }
}

