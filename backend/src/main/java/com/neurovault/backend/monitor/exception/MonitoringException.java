package com.neurovault.backend.monitor.exception;

/**
 * Exception for monitoring subsystem failures such as health evaluation errors
 * or analytics computation failures.
 *
 * @author NeuroVault Team
 */
public class MonitoringException extends RuntimeException {

    public MonitoringException(String message) {
        super(message);
    }

    public MonitoringException(String message, Throwable cause) {
        super(message, cause);
    }
}
