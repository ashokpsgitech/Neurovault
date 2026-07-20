package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing aggregated upload statistics for a user.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadStatisticsResponse {

    /** Total number of upload sessions created. */
    private int totalUploads;

    /** Number of completed uploads. */
    private int completedUploads;

    /** Number of failed uploads. */
    private int failedUploads;

    /** Number of uploads currently in progress. */
    private int inProgressUploads;

    /** Total bytes successfully uploaded across all sessions. */
    private long totalBytesUploaded;
}
