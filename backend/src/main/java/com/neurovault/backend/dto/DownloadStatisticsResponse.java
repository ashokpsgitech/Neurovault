package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing aggregated download statistics for a user.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DownloadStatisticsResponse {

    /** Total number of download sessions created. */
    private int totalDownloads;

    /** Number of completed downloads. */
    private int completedDownloads;

    /** Number of failed downloads. */
    private int failedDownloads;

    /** Number of downloads currently in progress. */
    private int inProgressDownloads;
}
