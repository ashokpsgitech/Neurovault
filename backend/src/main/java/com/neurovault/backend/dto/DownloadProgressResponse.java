package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO representing the current progress of a download session.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DownloadProgressResponse {

    /** The download session ID. */
    private UUID downloadId;

    /** The original file name. */
    private String fileName;

    /** Total number of chunks to download. */
    private int totalChunks;

    /** Number of chunks successfully downloaded and verified. */
    private int completedChunks;

    /** Download progress as a percentage (0.0 to 100.0). */
    private double progressPercent;

    /** Current status of the download session. */
    private String status;
}
