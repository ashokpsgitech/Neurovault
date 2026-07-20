package com.neurovault.backend.storage.exception;

import com.neurovault.backend.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

/**
 * Exception handler for storage-specific exceptions.
 * Scoped to the storage package controllers only, so it does not
 * interfere with the global exception handler.
 */
@RestControllerAdvice(basePackages = "com.neurovault.backend.storage")
public class StorageExceptionHandler {

    @ExceptionHandler(ContainerException.class)
    public ResponseEntity<ErrorResponse> handleContainerException(ContainerException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Container Error")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    @ExceptionHandler(StorageFullException.class)
    public ResponseEntity<ErrorResponse> handleStorageFullException(StorageFullException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INSUFFICIENT_STORAGE.value())
                .error("Insufficient Storage")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(response);
    }

    @ExceptionHandler(ChunkNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleChunkNotFoundException(ChunkNotFoundException ex) {
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error(HttpStatus.NOT_FOUND.getReasonPhrase())
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }
}
