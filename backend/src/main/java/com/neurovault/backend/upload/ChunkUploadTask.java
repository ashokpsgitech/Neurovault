package com.neurovault.backend.upload;

import com.neurovault.backend.encryption.ChunkStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Represents a single chunk upload task in the upload queue.
 * Tracks the chunk data, its parent session, and retry state.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkUploadTask {

    /** The upload session this task belongs to. */
    private UUID sessionId;

    /** The unique chunk identifier. */
    private UUID chunkId;

    /** The zero-based index of this chunk within the file. */
    private int chunkIndex;

    /** The encrypted chunk data bytes. */
    private byte[] data;

    /** Number of times this task has been retried. */
    @Builder.Default
    private int retryCount = 0;

    /** Current processing status of this task. */
    @Builder.Default
    private ChunkStatus status = ChunkStatus.PENDING;

    /**
     * Increments the retry counter and resets status to PENDING.
     */
    public void markForRetry() {
        this.retryCount++;
        this.status = ChunkStatus.PENDING;
    }
}
