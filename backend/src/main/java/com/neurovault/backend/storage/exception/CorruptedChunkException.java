package com.neurovault.backend.storage.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a stored chunk fails cryptographic checksum or integrity validation.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class CorruptedChunkException extends RuntimeException {

    public CorruptedChunkException(String message) {
        super(message);
    }

    public CorruptedChunkException(String message, Throwable cause) {
        super(message, cause);
    }
}
