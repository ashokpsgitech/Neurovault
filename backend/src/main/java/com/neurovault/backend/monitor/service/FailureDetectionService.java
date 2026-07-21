package com.neurovault.backend.monitor.service;

import com.neurovault.backend.entity.ChunkReplica;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.StorageContainer;
import com.neurovault.backend.monitor.model.HostHealthInfo;
import com.neurovault.backend.monitor.model.HostHealthStatus;
import com.neurovault.backend.replication.event.ClusterEventPublisher;
import com.neurovault.backend.replication.event.ClusterEventType;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.HostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service responsible for detecting host and replica failures and publishing
 * corresponding {@link com.neurovault.backend.replication.event.ClusterEvent}s.
 *
 * <p>Maintains an in-memory set of "already alerted" host IDs to prevent
 * event storms. The set is cleared when a host recovers to HEALTHY state,
 * allowing re-alerting if the same failure recurs.</p>
 *
 * @author NeuroVault Team
 */
@Slf4j
@Service
public class FailureDetectionService {

    private final ClusterHealthMonitor healthMonitor;
    private final ClusterEventPublisher eventPublisher;
    private final ChunkReplicaRepository chunkReplicaRepository;
    private final HostRepository hostRepository;

    /**
     * Tracks which hosts have already had events published for their current failure.
     * Key: hostId, Value: set of event types already published.
     */
    private final Map<UUID, Set<ClusterEventType>> alertedHosts = new ConcurrentHashMap<>();

    public FailureDetectionService(ClusterHealthMonitor healthMonitor,
                                   ClusterEventPublisher eventPublisher,
                                   ChunkReplicaRepository chunkReplicaRepository,
                                   HostRepository hostRepository) {
        this.healthMonitor = healthMonitor;
        this.eventPublisher = eventPublisher;
        this.chunkReplicaRepository = chunkReplicaRepository;
        this.hostRepository = hostRepository;
    }

    /**
     * Scans all hosts and publishes failure events for each detected issue.
     *
     * <p>Events are deduplicated: the same event type for the same host will
     * not be published again until the host recovers.</p>
     *
     * @return number of new failure events published
     */
    public int detectFailures() {
        log.debug("Running failure detection scan...");
        List<HostHealthInfo> allHosts = healthMonitor.evaluateAllHosts();
        int eventsPublished = 0;

        for (HostHealthInfo info : allHosts) {
            if (info.healthStatus() == HostHealthStatus.HEALTHY) {
                // Host recovered — clear alert history
                if (alertedHosts.containsKey(info.hostId())) {
                    log.info("Host {} recovered — clearing alert history", info.hostName());
                    alertedHosts.remove(info.hostId());
                    publishIfNew(info.hostId(), ClusterEventType.HOST_ONLINE,
                            "Host " + info.hostName() + " is back online");
                    eventsPublished++;
                }
                continue;
            }

            // Publish events based on health status
            switch (info.healthStatus()) {
                case OFFLINE -> {
                    if (publishIfNew(info.hostId(), ClusterEventType.HOST_OFFLINE,
                            "Host " + info.hostName() + " is OFFLINE")) {
                        eventsPublished++;
                    }
                }
                case HEARTBEAT_TIMEOUT, UNREACHABLE -> {
                    if (publishIfNew(info.hostId(), ClusterEventType.HEARTBEAT_TIMEOUT,
                            "Host " + info.hostName() + " heartbeat timed out")) {
                        eventsPublished++;
                    }
                }
                case LOW_STORAGE -> {
                    if (info.availableCapacity() <= 0) {
                        if (publishIfNew(info.hostId(), ClusterEventType.DISK_FULL,
                                "Host " + info.hostName() + " disk is FULL")) {
                            eventsPublished++;
                        }
                    } else {
                        if (publishIfNew(info.hostId(), ClusterEventType.LOW_STORAGE,
                                "Host " + info.hostName() + " has low storage")) {
                            eventsPublished++;
                        }
                    }
                }
                case CONTAINER_FAILURE -> {
                    ClusterEventType eventType = info.containerStatus() == StorageContainer.Status.CORRUPTED
                            ? ClusterEventType.CONTAINER_CORRUPTED
                            : ClusterEventType.CONTAINER_FAILURE;
                    if (publishIfNew(info.hostId(), eventType,
                            "Host " + info.hostName() + " container: " + info.containerStatus())) {
                        eventsPublished++;
                    }
                }
                default -> log.warn("Unhandled health status: {} for host {}",
                        info.healthStatus(), info.hostName());
            }
        }

        log.info("Failure detection complete: {} new events published", eventsPublished);
        return eventsPublished;
    }

    /**
     * Detects replicas in MISSING or CORRUPTED state and publishes REPLICA_LOST events.
     *
     * @return number of failed replicas detected
     */
    public int detectReplicaFailures() {
        log.debug("Scanning for failed replicas...");
        List<ChunkReplica> allReplicas = chunkReplicaRepository.findAll();
        int failedCount = 0;

        for (ChunkReplica replica : allReplicas) {
            if (replica.getStatus() == ChunkReplica.Status.MISSING
                    || replica.getStatus() == ChunkReplica.Status.CORRUPTED) {

                eventPublisher.publish(this, ClusterEventType.REPLICA_LOST,
                        replica.getHost().getId(), replica.getChunk().getId(),
                        String.format("Replica %s for chunk %s on host %s is %s",
                                replica.getId(), replica.getChunk().getId(),
                                replica.getHost().getId(), replica.getStatus()));
                failedCount++;
            }
        }

        // Also check for replicas on offline hosts that are still marked ACTIVE
        List<Host> offlineHosts = hostRepository.findByStatus(Host.Status.OFFLINE);
        for (Host offlineHost : offlineHosts) {
            List<ChunkReplica> hostReplicas = chunkReplicaRepository
                    .findByHostId(offlineHost.getId());
            for (ChunkReplica replica : hostReplicas) {
                if (replica.getStatus() == ChunkReplica.Status.ACTIVE) {
                    log.warn("Replica {} on offline host {} still marked ACTIVE — marking MISSING",
                            replica.getId(), offlineHost.getName());
                    replica.setStatus(ChunkReplica.Status.MISSING);
                    chunkReplicaRepository.save(replica);

                    eventPublisher.publish(this, ClusterEventType.REPLICA_LOST,
                            offlineHost.getId(), replica.getChunk().getId(),
                            String.format("Replica %s lost — host %s is offline",
                                    replica.getId(), offlineHost.getName()));
                    failedCount++;
                }
            }
        }

        log.info("Replica failure scan complete: {} issues found", failedCount);
        return failedCount;
    }

    /**
     * Returns the current alert state (for testing/diagnostics).
     */
    public Map<UUID, Set<ClusterEventType>> getAlertedHosts() {
        return Collections.unmodifiableMap(alertedHosts);
    }

    // ──────────────────────────────────────────────────────────────────────

    /**
     * Publishes an event only if the same event type hasn't already been published
     * for this host in the current failure window.
     *
     * @return true if the event was published (new), false if deduplicated
     */
    private boolean publishIfNew(UUID hostId, ClusterEventType eventType, String message) {
        Set<ClusterEventType> alerted = alertedHosts
                .computeIfAbsent(hostId, k -> ConcurrentHashMap.newKeySet());

        if (alerted.contains(eventType)) {
            return false; // Already alerted
        }

        alerted.add(eventType);
        eventPublisher.publishHostEvent(this, eventType, hostId, message);
        return true;
    }
}
