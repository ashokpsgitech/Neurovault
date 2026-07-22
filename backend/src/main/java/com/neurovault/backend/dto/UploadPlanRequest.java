package com.neurovault.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    @JsonAlias({"filename", "name"})
    private String filename;

    @JsonAlias({"fileSize", "sizeBytes", "size"})
    private Long fileSize;

    @JsonAlias({"totalChunks", "chunkCount", "chunks"})
    private Integer totalChunks;

    private String mimeType;

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
        return (checksum != null && !checksum.isBlank()) ? checksum : "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    }
}
