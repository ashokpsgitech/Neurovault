package com.neurovault.backend.replication.event;

/**
 * Enumeration of cluster events that can be detected and published
 * by the failure detection and self-healing subsystems.
 *
 * @author NeuroVault Team
 */
public enum ClusterEventType {

    // ── Host-level events ──────────────────────────────────────────────
    /** A host has transitioned to OFFLINE status. */
    HOST_OFFLINE,
    /** A host has transitioned back to ONLINE status. */
    HOST_ONLINE,
    /** A host's heartbeat has exceeded the configured timeout. */
    HEARTBEAT_TIMEOUT,
    /** A host's available storage has dropped below the threshold. */
    LOW_STORAGE,
    /** A host's available capacity has reached zero. */
    DISK_FULL,

    // ── Container-level events ─────────────────────────────────────────
    /** A host's storage container has become INACTIVE. */
    CONTAINER_FAILURE,
    /** A host's storage container has been marked CORRUPTED. */
    CONTAINER_CORRUPTED,

    // ── Replica-level events ───────────────────────────────────────────
    /** A replica has been lost (status changed to MISSING or host went offline). */
    REPLICA_LOST,
    /** A previously lost replica has been restored on a new host. */
    REPLICA_RESTORED,

    // ── Repair-level events ────────────────────────────────────────────
    /** A repair operation has been initiated for an under-replicated chunk. */
    REPAIR_INITIATED,
    /** A repair operation has completed successfully. */
    REPAIR_COMPLETED,
    /** A repair operation has failed. */
    REPAIR_FAILED,

    // Self-healing transfer events
    HEALING_CYCLE_STARTED,
    HEALING_CYCLE_COMPLETED,
    REPAIR_TRANSFER_STARTED,
    REPAIR_TRANSFER_COMPLETED,
    REPAIR_INTEGRITY_VERIFIED,
    REPAIR_METADATA_COMMITTED,
    REPAIR_NO_SOURCE_AVAILABLE,
    REPAIR_NO_DESTINATION_AVAILABLE
}
