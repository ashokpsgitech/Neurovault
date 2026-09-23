package com.neurovault.backend.replication.exception;

/**
 * Exception thrown when a target host lacks sufficient unreserved storage capacity
 * to accept a new chunk replica.
 */
public class InsufficientCapacityException extends ReplicationException {

    public InsufficientCapacityException(String message) {
        super(message);
    }

    public InsufficientCapacityException(String message, Throwable cause) {
        super(message, cause);
    }
}
