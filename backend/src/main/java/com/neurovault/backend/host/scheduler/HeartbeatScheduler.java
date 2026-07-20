package com.neurovault.backend.host.scheduler;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.host.service.HeartbeatService;
import com.neurovault.backend.repository.HostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled task that periodically checks all ONLINE hosts and marks them
 * as OFFLINE if they have missed too many consecutive heartbeats.
 *
 * <p>The check interval and miss threshold are configurable via application properties:
 * <ul>
 *   <li>{@code neurovault.host.heartbeat-check-interval-ms} — how often this check runs (default: 60000ms)</li>
 *   <li>{@code neurovault.host.heartbeat-miss-threshold} — how many intervals a host can miss before going OFFLINE (default: 3)</li>
 * </ul>
 */
@Component
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final HostRepository hostRepository;
    private final HeartbeatService heartbeatService;

    @Value("${neurovault.host.heartbeat-miss-threshold:3}")
    private int heartbeatMissThreshold;

    public HeartbeatScheduler(HostRepository hostRepository, HeartbeatService heartbeatService) {
        this.hostRepository = hostRepository;
        this.heartbeatService = heartbeatService;
    }

    /**
     * Runs periodically to detect stale hosts that have stopped sending heartbeats.
     * A host is considered stale if its last heartbeat exceeds
     * {@code heartbeatIntervalSeconds * heartbeatMissThreshold} seconds ago.
     */
    @Scheduled(fixedRateString = "${neurovault.host.heartbeat-check-interval-ms:60000}")
    public void checkStaleHosts() {
        log.debug("Running stale host detection check...");

        List<Host> onlineHosts = hostRepository.findByStatus(Host.Status.ONLINE);

        if (onlineHosts.isEmpty()) {
            log.debug("No ONLINE hosts to check");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int staleCount = 0;

        for (Host host : onlineHosts) {
            LocalDateTime lastHeartbeat = host.getLastHeartbeat();

            // If the host has never sent a heartbeat, use its creation time
            if (lastHeartbeat == null) {
                lastHeartbeat = host.getCreatedAt();
            }

            long maxMissSeconds = (long) host.getHeartbeatIntervalSeconds() * heartbeatMissThreshold;
            LocalDateTime threshold = now.minusSeconds(maxMissSeconds);

            if (lastHeartbeat.isBefore(threshold)) {
                heartbeatService.markHostOffline(host.getId());
                staleCount++;
            }
        }

        if (staleCount > 0) {
            log.info("Marked {} host(s) as OFFLINE due to missed heartbeats", staleCount);
        }
    }
}
