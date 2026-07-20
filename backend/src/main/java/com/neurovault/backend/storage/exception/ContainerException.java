package com.neurovault.backend.storage.exception;

/**
 * Runtime exception for container-level I/O failures, integrity errors,
 * and invalid container state operations.
 */
public class ContainerException extends RuntimeException {

    public ContainerException(String message) {
        super(message);
    }

    public ContainerException(String message, Throwable cause) {
        super(message, cause);
    }
}
