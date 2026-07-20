package com.neurovault.backend.encryption;

/**
 * Represents the lifecycle status of a file chunk during upload/download processing.
 */
public enum ChunkStatus {

    /** Chunk has been created but not yet uploaded to a host. */
    PENDING,

    /** Chunk has been successfully uploaded to at least one host. */
    UPLOADED,

    /** Chunk integrity has been verified after upload or download. */
    VERIFIED,

    /** Chunk upload or verification failed. */
    FAILED
}
