package com.neurovault.backend.replication.controller;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.monitor.model.HostHealthInfo;
import com.neurovault.backend.monitor.service.ClusterAnalyticsService;
import com.neurovault.backend.monitor.service.ClusterHealthMonitor;
import com.neurovault.backend.replication.dto.*;
import com.neurovault.backend.replication.service.ReplicationService;
import com.neurovault.backend.replication.service.SelfHealingService;
import com.neurovault.backend.repository.HostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller providing administrative and monitoring endpoints for the NeuroVault cluster.
 *
 * <p>Exposes operations to query cluster summary statistics, host health states,
 * chunk replica placements, and manually trigger self-healing repair runs.</p>
 *
 * @author NeuroVault Team
 */
@Slf4j
@RestController
@RequestMapping("/api/cluster")
public class ClusterController {

    private final ClusterAnalyticsService analyticsService;
    private final ClusterHealthMonitor healthMonitor;
    private final ReplicationService replicationService;
    private final SelfHealingService selfHealingService;
    private final HostRepository hostRepository;

    public ClusterController(ClusterAnalyticsService analyticsService,
                             ClusterHealthMonitor healthMonitor,
                             ReplicationService replicationService,
                             SelfHealingService selfHealingService,
                             HostRepository hostRepository) {
        this.analyticsService = analyticsService;
        this.healthMonitor = healthMonitor;
        this.replicationService = replicationService;
        this.selfHealingService = selfHealingService;
        this.hostRepository = hostRepository;
    }

    /**
     * GET /api/cluster/status
     * Returns a summary status DTO of the whole cluster.
     */
    @GetMapping("/status")
    public ResponseEntity<ClusterStatusResponse> getClusterStatus() {
        log.info("REST Request to get cluster summary status");
        ClusterStatusResponse response = mapToClusterStatusResponse();
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/cluster/hosts
     * Returns status and health details for all registered host devices.
     */
    @GetMapping("/hosts")
    public ResponseEntity<List<HostStatusDto>> getAllHosts() {
        log.info("REST Request to get all host status listings");
        List<Host> hosts = hostRepository.findAll();
        List<HostStatusDto> statusList = hosts.stream()
                .map(host -> {
                    HostHealthInfo healthInfo = healthMonitor.evaluateHostHealth(host);
                    return HostStatusDto.builder()
                            .hostId(host.getId())
                            .name(host.getName())
                            .status(host.getStatus())
                            .healthStatus(healthInfo.healthStatus())
                            .deviceType(host.getDeviceType())
                            .operatingSystem(host.getOperatingSystem())
                            .totalCapacityBytes(host.getTotalCapacityBytes())
                            .usedCapacityBytes(host.getUsedCapacityBytes())
                            .reservedCapacityBytes(host.getReservedCapacityBytes())
                            .availableCapacityBytes(healthInfo.availableCapacity())
                            .usagePercent(healthInfo.usagePercent())
                            .replicaCount(healthInfo.replicaCount())
                            .lastHeartbeat(host.getLastHeartbeat())
                            .containerStatus(healthInfo.containerStatus())
                            .cpuUsagePercent(healthInfo.cpuUsage())
                            .memoryUsagePercent(healthInfo.memoryUsage())
                            .issues(healthInfo.issues())
                            .build();
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(statusList);
    }

    /**
     * GET /api/cluster/replicas
     * Returns replication status details for all chunks across the cluster.
     */
    @GetMapping("/replicas")
    public ResponseEntity<List<ReplicaInfoDto>> getReplicaInfo() {
        log.info("REST Request to get all replica information");
        List<ReplicaInfoDto> replicas = replicationService.generateAllReplicaMetadata();
        return ResponseEntity.ok(replicas);
    }

    /**
     * POST /api/cluster/repair
     * Manually triggers the cluster self-healing repair tasks to restore target replication levels.
     */
    @PostMapping("/repair")
    public ResponseEntity<RepairResultDto> triggerManualRepair() {
        log.info("REST Request to manually trigger cluster repair");
        RepairResultDto result = selfHealingService.runHealingCycle();
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/cluster/health
     * Returns overall health status (HEALTHY, DEGRADED, CRITICAL) and active issues list.
     */
    @GetMapping("/health")
    public ResponseEntity<ClusterHealthResponse> getClusterHealth() {
        log.info("REST Request to evaluate overall cluster health");
        ClusterHealthResponse response = healthMonitor.getOverallClusterHealth();
        return ResponseEntity.ok(response);
    }

    // ──────────────────────────────────────────────────────────────────────

    /**
     * Helper to map ClusterStatistics to the expected ClusterStatusResponse DTO.
     */
    private ClusterStatusResponse mapToClusterStatusResponse() {
        var stats = analyticsService.computeStatistics();
        return ClusterStatusResponse.builder()
                .totalHosts(stats.getTotalHosts())
                .onlineHosts(stats.getOnlineHosts())
                .offlineHosts(stats.getOfflineHosts())
                .totalStorageBytes(stats.getTotalStorageBytes())
                .usedStorageBytes(stats.getUsedStorageBytes())
                .availableStorageBytes(stats.getAvailableStorageBytes())
                .totalFiles(stats.getTotalFiles())
                .totalChunks(stats.getTotalChunks())
                .totalReplicas(stats.getTotalReplicas())
                .activeReplicas(stats.getActiveReplicas())
                .missingReplicas(stats.getMissingReplicas())
                .corruptedReplicas(stats.getCorruptedReplicas())
                .repairCount(stats.getRepairCount())
                .recoveryCount(stats.getRecoveryCount())
                .averageReplicationFactor(stats.getAverageReplicationFactor())
                .clusterUtilizationPercent(stats.getClusterUtilizationPercent())
                .timestamp(stats.getTimestamp())
                .build();
    }
}
