package com.neurovault.backend.replication.exception;

/**
 * General-purpose exception for replication subsystem failures
 * such as assignment errors, status transition violations, or metadata inconsistencies.
 *
 * @author NeuroVault Team
 */
public class ReplicationException extends RuntimeException {

    public ReplicationException(String message) {
        super(message);
    }

    public ReplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
