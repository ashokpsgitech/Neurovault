package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO representing the response after initiating a file upload.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadResponse {

    /** The upload session ID (used for progress tracking, retry, cancel). */
    private UUID uploadId;

    /** The file metadata ID. */
    private UUID fileId;

    /** The original file name. */
    private String fileName;

    /** The original file size in bytes. */
    private long fileSize;

    /** Total number of chunks the file was split into. */
    private int totalChunks;

    /** Current status of the upload session. */
    private String status;

    /** Timestamp when the upload session was created. */
    private LocalDateTime createdAt;

    /** The RSA-encrypted AES key (Base64). Client needs this for future download. */
    private String encryptedAesKey;
}
