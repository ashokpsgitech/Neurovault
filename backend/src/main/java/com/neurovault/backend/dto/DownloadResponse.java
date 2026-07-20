package com.neurovault.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO representing the response after initiating a file download.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DownloadResponse {

    /** The download session ID. */
    private UUID downloadId;

    /** The file metadata ID. */
    private UUID fileId;

    /** The original file name. */
    private String fileName;

    /** The original file size in bytes. */
    private long fileSize;

    /** Total number of chunks to download. */
    private int totalChunks;

    /** Current status of the download session. */
    private String status;
}
