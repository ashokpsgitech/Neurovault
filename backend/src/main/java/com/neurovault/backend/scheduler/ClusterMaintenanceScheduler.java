package com.neurovault.backend.scheduler;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.monitor.service.ClusterAnalyticsService;
import com.neurovault.backend.monitor.service.ClusterHealthMonitor;
import com.neurovault.backend.monitor.service.FailureDetectionService;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.replication.service.LoadBalancingService;
import com.neurovault.backend.replication.service.SelfHealingService;
import com.neurovault.backend.repository.HostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Scheduler component that runs every 30 seconds to perform cluster maintenance.
 *
 * <p>Maintenance cycle workflow:</p>
 * <ol>
 *   <li>Evaluate host health and detect timeouts</li>
 *   <li>Publish events for newly detected failures</li>
 *   <li>Run self-healing repair tasks for under-replicated chunks</li>
 *   <li>Perform cleanups (e.g., mark hosts offline if heartbeats timed out)</li>
 *   <li>Execute load balancing migrations if required</li>
 *   <li>Update cached cluster statistics</li>
 * </ol>
 *
 * @author NeuroVault Team
 */
@Slf4j
@Component
public class ClusterMaintenanceScheduler {

    private final ClusterHealthMonitor healthMonitor;
    private final FailureDetectionService failureDetectionService;
    private final SelfHealingService selfHealingService;
    private final LoadBalancingService loadBalancingService;
    private final ClusterAnalyticsService analyticsService;
    private final HostRepository hostRepository;
    private final ReplicationConfig replicationConfig;

    public ClusterMaintenanceScheduler(ClusterHealthMonitor healthMonitor,
                                       FailureDetectionService failureDetectionService,
                                       SelfHealingService selfHealingService,
                                       LoadBalancingService loadBalancingService,
                                       ClusterAnalyticsService analyticsService,
                                       HostRepository hostRepository,
                                       ReplicationConfig replicationConfig) {
        this.healthMonitor = healthMonitor;
        this.failureDetectionService = failureDetectionService;
        this.selfHealingService = selfHealingService;
        this.loadBalancingService = loadBalancingService;
        this.analyticsService = analyticsService;
        this.hostRepository = hostRepository;
        this.replicationConfig = replicationConfig;
    }

    /**
     * Periodic task executing cluster health checks, repairs, and statistics updates.
     * Uses a fixed delay configuration matching replicationConfig properties.
     */
    @Scheduled(fixedDelayString = "${neurovault.replication.scheduler-interval-ms:30000}")
    public void runMaintenanceCycle() {
        log.info("Starting scheduled cluster maintenance cycle...");
        try {
            // 1. Run Health Checks & Timeout Cleanups
            cleanupTimedOutHosts();

            // 2. Failure Detection (publishes events for failures)
            failureDetectionService.detectFailures();
            failureDetectionService.detectReplicaFailures();

            // 3. Self-healing Repair Tasks
            selfHealingService.runHealingCycle();

            // 4. Load Balancing
            if (loadBalancingService.analyzeDistribution()) {
                log.info("Cluster imbalance detected, initiating load balancing...");
                loadBalancingService.rebalanceCluster();
            }

            // 5. Update Cluster Statistics
            analyticsService.forceRefresh();

            log.info("Scheduled cluster maintenance cycle completed successfully.");
        } catch (Exception e) {
            log.error("Error occurred during scheduled maintenance cycle", e);
        }
    }

    /**
     * Scans for ONLINE hosts that have missed heartbeats and marks them OFFLINE.
     */
    @Transactional
    public void cleanupTimedOutHosts() {
        log.debug("Checking for timed-out host heartbeats...");
        List<Host> onlineHosts = hostRepository.findByStatus(Host.Status.ONLINE);
        int markedOffline = 0;

        for (Host host : onlineHosts) {
            if (healthMonitor.isHeartbeatTimedOut(host)) {
                log.warn("Host {} (ID: {}) heartbeat has timed out. Marking status as OFFLINE.",
                        host.getName(), host.getId());
                host.setStatus(Host.Status.OFFLINE);
                hostRepository.save(host);
                markedOffline++;
            }
        }

        if (markedOffline > 0) {
            log.info("Marked {} hosts OFFLINE due to heartbeat timeouts.", markedOffline);
            analyticsService.invalidateCache();
        }
    }
}
