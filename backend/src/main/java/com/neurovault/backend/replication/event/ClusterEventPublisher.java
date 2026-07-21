package com.neurovault.backend.replication.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Convenience wrapper around Spring's {@link ApplicationEventPublisher}
 * for publishing {@link ClusterEvent}s with structured logging.
 *
 * <p>All cluster subsystems should publish events through this component
 * rather than using the raw publisher directly.</p>
 *
 * @author NeuroVault Team
 */
@Slf4j
@Component
public class ClusterEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public ClusterEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Publishes a cluster event with full context.
     *
     * @param source    the object originating the event
     * @param eventType the type of event
     * @param hostId    the affected host (nullable)
     * @param chunkId   the affected chunk (nullable)
     * @param message   human-readable description
     */
    public void publish(Object source, ClusterEventType eventType,
                        UUID hostId, UUID chunkId, String message) {
        ClusterEvent event = new ClusterEvent(source, eventType, hostId, chunkId, message);
        log.info("Publishing cluster event: {}", event);
        applicationEventPublisher.publishEvent(event);
    }

    /**
     * Publishes a host-level event (no chunk context).
     */
    public void publishHostEvent(Object source, ClusterEventType eventType,
                                 UUID hostId, String message) {
        publish(source, eventType, hostId, null, message);
    }

    /**
     * Publishes a chunk-level event (no host context).
     */
    public void publishChunkEvent(Object source, ClusterEventType eventType,
                                  UUID chunkId, String message) {
        publish(source, eventType, null, chunkId, message);
    }
}
