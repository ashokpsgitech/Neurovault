package com.neurovault.backend.replication.event;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event representing a notable occurrence within the NeuroVault cluster.
 *
 * <p>Published via Spring's {@link org.springframework.context.ApplicationEventPublisher}
 * and consumed by the self-healing engine, analytics service, and audit logger.</p>
 *
 * @author NeuroVault Team
 */
public class ClusterEvent extends ApplicationEvent {

    private final ClusterEventType eventType;
    private final UUID hostId;
    private final UUID chunkId;
    private final String message;
    private final LocalDateTime eventTimestamp;

    /**
     * Constructs a new cluster event.
     *
     * @param source    the object that published this event
     * @param eventType the type of cluster event
     * @param hostId    the affected host (nullable for chunk-only events)
     * @param chunkId   the affected chunk (nullable for host-only events)
     * @param message   human-readable description
     */
    public ClusterEvent(Object source, ClusterEventType eventType,
                        UUID hostId, UUID chunkId, String message) {
        super(source);
        this.eventType = eventType;
        this.hostId = hostId;
        this.chunkId = chunkId;
        this.message = message;
        this.eventTimestamp = LocalDateTime.now();
    }

    public ClusterEventType getEventType() {
        return eventType;
    }

    public UUID getHostId() {
        return hostId;
    }

    public UUID getChunkId() {
        return chunkId;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getEventTimestamp() {
        return eventTimestamp;
    }

    @Override
    public String toString() {
        return String.format("ClusterEvent{type=%s, hostId=%s, chunkId=%s, message='%s', time=%s}",
                eventType, hostId, chunkId, message, eventTimestamp);
    }
}
