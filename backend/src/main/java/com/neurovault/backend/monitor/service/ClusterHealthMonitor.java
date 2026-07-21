package com.neurovault.backend.monitor.service;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.HostHeartbeat;
import com.neurovault.backend.entity.StorageContainer;
import com.neurovault.backend.monitor.model.HostHealthInfo;
import com.neurovault.backend.monitor.model.HostHealthStatus;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.replication.dto.ClusterHealthResponse;
import com.neurovault.backend.replication.dto.ClusterHealthResponse.HealthLevel;
import com.neurovault.backend.replication.service.ReplicationService;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.HostHeartbeatRepository;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.StorageContainerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Central monitoring service that evaluates the health of individual hosts
 * and the cluster as a whole.
 *
 * <p>Health evaluation considers:</p>
 * <ul>
 *   <li>Host online/offline status</li>
 *   <li>Heartbeat recency (timeout detection)</li>
 *   <li>Storage capacity thresholds</li>
 *   <li>Storage container status</li>
 * </ul>
 *
 * <p>Overall cluster health is classified as:</p>
 * <ul>
 *   <li><strong>HEALTHY</strong> — All hosts are healthy, no under-replicated chunks</li>
 *   <li><strong>DEGRADED</strong> — Some hosts have issues but no data-loss risk</li>
 *   <li><strong>CRITICAL</strong> — Under-replicated chunks exist or &gt;50% hosts are offline</li>
 * </ul>
 *
 * @author NeuroVault Team
 */
@Slf4j
@Service
public class ClusterHealthMonitor {

    private final HostRepository hostRepository;
    private final HostHeartbeatRepository heartbeatRepository;
    private final StorageContainerRepository containerRepository;
    private final ChunkReplicaRepository chunkReplicaRepository;
    private final ReplicationConfig config;
    private final ReplicationService replicationService;

    public ClusterHealthMonitor(HostRepository hostRepository,
                                HostHeartbeatRepository heartbeatRepository,
                                StorageContainerRepository containerRepository,
                                ChunkReplicaRepository chunkReplicaRepository,
                                ReplicationConfig config,
                                ReplicationService replicationService) {
        this.hostRepository = hostRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.containerRepository = containerRepository;
        this.chunkReplicaRepository = chunkReplicaRepository;
        this.config = config;
        this.replicationService = replicationService;
    }

    /**
     * Evaluates the health of a single host.
     *
     * @param host the host entity to evaluate
     * @return a snapshot of the host's health
     */
    public HostHealthInfo evaluateHostHealth(Host host) {
        List<String> issues = new ArrayList<>();
        HostHealthStatus healthStatus = HostHealthStatus.HEALTHY;

        // ── 1. Check entity-level status ─────────────────────────────────
        if (host.getStatus() == Host.Status.OFFLINE) {
            healthStatus = HostHealthStatus.OFFLINE;
            issues.add("Host is OFFLINE");
        }

        // ── 2. Check heartbeat recency ───────────────────────────────────
        boolean heartbeatTimedOut = isHeartbeatTimedOut(host);
        if (heartbeatTimedOut) {
            if (host.getStatus() == Host.Status.ONLINE) {
                healthStatus = HostHealthStatus.UNREACHABLE;
                issues.add("Host is ONLINE but heartbeat has timed out — possibly unreachable");
            } else {
                healthStatus = HostHealthStatus.HEARTBEAT_TIMEOUT;
                issues.add("Heartbeat timed out (last: " + host.getLastHeartbeat() + ")");
            }
        }

        // ── 3. Check storage container ───────────────────────────────────
        StorageContainer.Status containerStatus = getContainerStatus(host);
        if (containerStatus == StorageContainer.Status.CORRUPTED) {
            if (healthStatus == HostHealthStatus.HEALTHY) {
                healthStatus = HostHealthStatus.CONTAINER_FAILURE;
            }
            issues.add("Storage container is CORRUPTED");
        } else if (containerStatus == StorageContainer.Status.INACTIVE) {
            if (healthStatus == HostHealthStatus.HEALTHY) {
                healthStatus = HostHealthStatus.CONTAINER_FAILURE;
            }
            issues.add("Storage container is INACTIVE");
        } else if (containerStatus == null) {
            issues.add("No storage container registered");
        }

        // ── 4. Check capacity ────────────────────────────────────────────
        long available = Math.max(0, host.getTotalCapacityBytes()
                - host.getUsedCapacityBytes()
                - host.getReservedCapacityBytes());
        double usagePercent = host.getTotalCapacityBytes() > 0
                ? (double) host.getUsedCapacityBytes() / host.getTotalCapacityBytes() * 100.0
                : 0.0;
        double availablePercent = host.getTotalCapacityBytes() > 0
                ? (double) available / host.getTotalCapacityBytes() * 100.0
                : 0.0;

        if (available <= 0) {
            issues.add("Disk is FULL — no available capacity");
            if (healthStatus == HostHealthStatus.HEALTHY) {
                healthStatus = HostHealthStatus.LOW_STORAGE;
            }
        } else if (availablePercent < config.getLowStorageThresholdPercent()) {
            issues.add(String.format("Low storage — %.1f%% available (threshold: %d%%)",
                    availablePercent, config.getLowStorageThresholdPercent()));
            if (healthStatus == HostHealthStatus.HEALTHY) {
                healthStatus = HostHealthStatus.LOW_STORAGE;
            }
        }

        // ── 5. Gather latest heartbeat metrics ───────────────────────────
        Double cpuUsage = null;
        Double memoryUsage = null;
        List<HostHeartbeat> heartbeats = heartbeatRepository
                .findByHostIdOrderByTimestampDesc(host.getId());
        if (!heartbeats.isEmpty()) {
            HostHeartbeat latest = heartbeats.get(0);
            cpuUsage = latest.getCpuUsagePercent();
            memoryUsage = latest.getMemoryUsagePercent();
        }

        // ── 6. Count replicas ────────────────────────────────────────────
        int replicaCount = chunkReplicaRepository.findByHostId(host.getId()).size();

        return new HostHealthInfo(
                host.getId(),
                host.getName(),
                host.getStatus(),
                healthStatus,
                cpuUsage,
                memoryUsage,
                host.getTotalCapacityBytes(),
                host.getUsedCapacityBytes(),
                available,
                usagePercent,
                replicaCount,
                host.getLastHeartbeat(),
                containerStatus,
                issues
        );
    }

    /**
     * Evaluates the health of all registered hosts.
     *
     * @return list of health snapshots for every host
     */
    public List<HostHealthInfo> evaluateAllHosts() {
        List<Host> allHosts = hostRepository.findAll();
        List<HostHealthInfo> results = allHosts.stream()
                .map(this::evaluateHostHealth)
                .collect(Collectors.toList());

        long healthy = results.stream()
                .filter(h -> h.healthStatus() == HostHealthStatus.HEALTHY)
                .count();
        log.info("Cluster health scan: {}/{} hosts healthy", healthy, results.size());

        return results;
    }

    /**
     * Returns only healthy hosts.
     */
    public List<HostHealthInfo> getHealthyHosts() {
        return evaluateAllHosts().stream()
                .filter(h -> h.healthStatus() == HostHealthStatus.HEALTHY)
                .collect(Collectors.toList());
    }

    /**
     * Returns only offline hosts.
     */
    public List<HostHealthInfo> getOfflineHosts() {
        return evaluateAllHosts().stream()
                .filter(h -> h.healthStatus() == HostHealthStatus.OFFLINE)
                .collect(Collectors.toList());
    }

    /**
     * Returns hosts that are unreachable (ONLINE status but heartbeat timed out).
     */
    public List<HostHealthInfo> getUnreachableHosts() {
        return evaluateAllHosts().stream()
                .filter(h -> h.healthStatus() == HostHealthStatus.UNREACHABLE)
                .collect(Collectors.toList());
    }

    /**
     * Returns hosts with low storage.
     */
    public List<HostHealthInfo> getLowStorageHosts() {
        return evaluateAllHosts().stream()
                .filter(h -> h.healthStatus() == HostHealthStatus.LOW_STORAGE)
                .collect(Collectors.toList());
    }

    /**
     * Checks whether a host's heartbeat has exceeded the configured timeout.
     *
     * @param host the host to check
     * @return true if the heartbeat is timed out
     */
    public boolean isHeartbeatTimedOut(Host host) {
        if (host.getLastHeartbeat() == null) {
            // Newly registered hosts with no heartbeat: consider timed out only
            // if the host was created more than timeout seconds ago
            return host.getCreatedAt() != null
                    && host.getCreatedAt().plusSeconds(config.getHeartbeatTimeoutSeconds())
                    .isBefore(LocalDateTime.now());
        }
        return host.getLastHeartbeat()
                .plusSeconds(config.getHeartbeatTimeoutSeconds())
                .isBefore(LocalDateTime.now());
    }

    /**
     * Computes the overall cluster health response.
     *
     * @return cluster health assessment with issues list
     */
    public ClusterHealthResponse getOverallClusterHealth() {
        List<HostHealthInfo> allHosts = evaluateAllHosts();
        List<String> issues = new ArrayList<>();

        long healthyCount = allHosts.stream()
                .filter(h -> h.healthStatus() == HostHealthStatus.HEALTHY)
                .count();
        long unhealthyCount = allHosts.size() - healthyCount;

        // Gather all host issues
        allHosts.stream()
                .filter(h -> !h.issues().isEmpty())
                .forEach(h -> h.issues().forEach(issue ->
                        issues.add(h.hostName() + ": " + issue)));

        // Count under-replicated chunks
        Map<UUID, Integer> underReplicated = replicationService.getUnderReplicatedChunks();
        long underReplicatedCount = underReplicated.size();

        if (underReplicatedCount > 0) {
            issues.add(underReplicatedCount + " chunks are under-replicated");
        }

        // ── Classify health level ────────────────────────────────────────
        HealthLevel level;
        if (allHosts.isEmpty()) {
            level = HealthLevel.CRITICAL;
            issues.add("No hosts registered in the cluster");
        } else if (underReplicatedCount > 0
                || (allHosts.size() > 0 && unhealthyCount > allHosts.size() / 2)) {
            level = HealthLevel.CRITICAL;
        } else if (unhealthyCount > 0) {
            level = HealthLevel.DEGRADED;
        } else {
            level = HealthLevel.HEALTHY;
        }

        return ClusterHealthResponse.builder()
                .healthLevel(level)
                .issues(issues)
                .healthyHostCount((int) healthyCount)
                .unhealthyHostCount((int) unhealthyCount)
                .underReplicatedChunks(underReplicatedCount)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────

    private StorageContainer.Status getContainerStatus(Host host) {
        return containerRepository.findByHostId(host.getId())
                .map(StorageContainer::getStatus)
                .orElse(null);
    }
}
