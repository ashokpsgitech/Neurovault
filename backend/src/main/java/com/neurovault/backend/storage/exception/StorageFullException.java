package com.neurovault.backend.storage.exception;

/**
 * Exception thrown when the storage container has insufficient capacity
 * to store a new chunk.
 */
public class StorageFullException extends RuntimeException {

    public StorageFullException(String message) {
        super(message);
    }
}
