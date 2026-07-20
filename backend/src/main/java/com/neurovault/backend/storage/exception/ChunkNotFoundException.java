package com.neurovault.backend.storage.exception;

/**
 * Exception thrown when a requested chunk ID does not exist
 * in the container's metadata index.
 */
public class ChunkNotFoundException extends RuntimeException {

    public ChunkNotFoundException(String message) {
        super(message);
    }
}
