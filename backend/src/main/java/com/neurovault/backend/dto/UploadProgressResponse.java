package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO representing the current progress of an upload session.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadProgressResponse {

    /** The upload session ID. */
    private UUID uploadId;

    /** The original file name. */
    private String fileName;

    /** Total number of chunks in the file. */
    private int totalChunks;

    /** Number of chunks successfully uploaded. */
    private int completedChunks;

    /** Number of chunks that failed to upload. */
    private int failedChunks;

    /** Upload progress as a percentage (0.0 to 100.0). */
    private double progressPercent;

    /** Current status of the upload session. */
    private String status;
}
