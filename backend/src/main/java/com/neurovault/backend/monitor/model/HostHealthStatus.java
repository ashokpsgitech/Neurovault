package com.neurovault.backend.monitor.model;

/**
 * Enumeration of possible health classifications for a storage host.
 *
 * <p>Used by the {@link com.neurovault.backend.monitor.service.ClusterHealthMonitor}
 * to categorise each host based on its heartbeat, capacity, and container state.</p>
 *
 * @author NeuroVault Team
 */
public enum HostHealthStatus {

    /** Host is online, heartbeat is recent, storage and container are healthy. */
    HEALTHY,

    /** Host's last heartbeat exceeds the configured timeout threshold. */
    HEARTBEAT_TIMEOUT,

    /** Host's available storage capacity is below the configured low-storage threshold. */
    LOW_STORAGE,

    /** Host's storage container is INACTIVE or CORRUPTED. */
    CONTAINER_FAILURE,

    /** Host entity status is OFFLINE. */
    OFFLINE,

    /**
     * Host entity status is ONLINE, but the heartbeat has timed out.
     * Indicates a contradictory state — the host might be unreachable.
     */
    UNREACHABLE
}
