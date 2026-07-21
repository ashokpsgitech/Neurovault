package com.neurovault.backend.replication.exception;

/**
 * Thrown when the cluster does not have enough healthy, eligible hosts
 * to satisfy the required replication factor for a chunk.
 *
 * @author NeuroVault Team
 */
public class InsufficientHostsException extends RuntimeException {

    private final int required;
    private final int available;

    public InsufficientHostsException(int required, int available) {
        super(String.format(
                "Insufficient hosts for replication: required=%d, available=%d",
                required, available));
        this.required = required;
        this.available = available;
    }

    public InsufficientHostsException(String message) {
        super(message);
        this.required = 0;
        this.available = 0;
    }

    public int getRequired() {
        return required;
    }

    public int getAvailable() {
        return available;
    }
}
