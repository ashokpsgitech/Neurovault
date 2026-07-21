package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.StorageContainer;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.HostHeartbeatRepository;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.StorageContainerRepository;
import com.neurovault.backend.entity.HostHeartbeat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Default {@link HostSelectionStrategy} implementation using a weighted scoring algorithm.
 *
 * <p>Each eligible host is scored across four dimensions:</p>
 * <table>
 *   <tr><th>Factor</th><th>Weight</th><th>Calculation</th></tr>
 *   <tr><td>Available capacity ratio</td><td>0.35</td><td>(total − used − reserved) / total</td></tr>
 *   <tr><td>Load (inverse CPU)</td><td>0.25</td><td>1 − (cpuUsage / 100)</td></tr>
 *   <tr><td>Existing replica count (inverse)</td><td>0.25</td><td>1 / (1 + replicaCount)</td></tr>
 *   <tr><td>Heartbeat recency</td><td>0.15</td><td>Normalised time since last heartbeat</td></tr>
 * </table>
 *
 * <p>Hosts are filtered before scoring:</p>
 * <ul>
 *   <li>OFFLINE hosts are excluded</li>
 *   <li>Hosts with timed-out heartbeats are excluded</li>
 *   <li>Hosts without an ACTIVE storage container are excluded</li>
 *   <li>Hosts with insufficient available capacity are excluded</li>
 *   <li>Hosts in the {@code excludeHostIds} set are excluded</li>
 * </ul>
 *
 * @author NeuroVault Team
 */
@Slf4j
@Component
public class WeightedScoreHostSelectionStrategy implements HostSelectionStrategy {

    // ── Scoring weights (must sum to 1.0) ────────────────────────────────
    private static final double WEIGHT_CAPACITY = 0.35;
    private static final double WEIGHT_LOAD = 0.25;
    private static final double WEIGHT_REPLICA_SPREAD = 0.25;
    private static final double WEIGHT_HEARTBEAT_RECENCY = 0.15;

    private final HostRepository hostRepository;
    private final HostHeartbeatRepository heartbeatRepository;
    private final ChunkReplicaRepository chunkReplicaRepository;
    private final StorageContainerRepository containerRepository;
    private final ReplicationConfig config;

    public WeightedScoreHostSelectionStrategy(HostRepository hostRepository,
                                              HostHeartbeatRepository heartbeatRepository,
                                              ChunkReplicaRepository chunkReplicaRepository,
                                              StorageContainerRepository containerRepository,
                                              ReplicationConfig config) {
        this.hostRepository = hostRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.chunkReplicaRepository = chunkReplicaRepository;
        this.containerRepository = containerRepository;
        this.config = config;
    }

    @Override
    public List<Host> selectHosts(int count, long chunkSizeBytes, Set<UUID> excludeHostIds) {
        log.debug("Selecting {} hosts for chunk of {} bytes, excluding {}",
                count, chunkSizeBytes, excludeHostIds);

        List<Host> allHosts = hostRepository.findAll();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime heartbeatCutoff = now.minusSeconds(config.getHeartbeatTimeoutSeconds());

        // ── Filter eligible candidates ─────────────────────────────────
        List<Host> candidates = allHosts.stream()
                .filter(host -> host.getStatus() == Host.Status.ONLINE)
                .filter(host -> !excludeHostIds.contains(host.getId()))
                .filter(host -> isHeartbeatRecent(host, heartbeatCutoff))
                .filter(host -> hasActiveContainer(host))
                .filter(host -> getAvailableCapacity(host) >= chunkSizeBytes)
                .collect(Collectors.toList());

        log.debug("Found {} eligible candidates from {} total hosts",
                candidates.size(), allHosts.size());

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // ── Score and rank ─────────────────────────────────────────────
        List<ScoredHost> scoredHosts = candidates.stream()
                .map(host -> new ScoredHost(host, computeScore(host, now)))
                .sorted(Comparator.comparingDouble(ScoredHost::score).reversed())
                .toList();

        List<Host> selected = scoredHosts.stream()
                .limit(count)
                .map(ScoredHost::host)
                .collect(Collectors.toList());

        if (log.isDebugEnabled()) {
            scoredHosts.stream().limit(count).forEach(sh ->
                    log.debug("  Selected host {} (name={}) with score {:.4f}",
                            sh.host().getId(), sh.host().getName(), sh.score()));
        }

        return selected;
    }

    @Override
    public String getStrategyName() {
        return "WeightedScore";
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scoring logic
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Computes a composite score in [0.0, 1.0] for a candidate host.
     */
    double computeScore(Host host, LocalDateTime now) {
        double capacityScore = computeCapacityScore(host);
        double loadScore = computeLoadScore(host);
        double replicaSpreadScore = computeReplicaSpreadScore(host);
        double heartbeatScore = computeHeartbeatRecencyScore(host, now);

        return (WEIGHT_CAPACITY * capacityScore)
                + (WEIGHT_LOAD * loadScore)
                + (WEIGHT_REPLICA_SPREAD * replicaSpreadScore)
                + (WEIGHT_HEARTBEAT_RECENCY * heartbeatScore);
    }

    /**
     * Higher score for hosts with more available capacity relative to total.
     */
    private double computeCapacityScore(Host host) {
        if (host.getTotalCapacityBytes() <= 0) {
            return 0.0;
        }
        long available = getAvailableCapacity(host);
        return Math.max(0.0, (double) available / host.getTotalCapacityBytes());
    }

    /**
     * Higher score for hosts with lower CPU usage.
     * Falls back to 0.5 (neutral) if no heartbeat data is available.
     */
    private double computeLoadScore(Host host) {
        List<HostHeartbeat> heartbeats = heartbeatRepository
                .findByHostIdOrderByTimestampDesc(host.getId());

        if (heartbeats.isEmpty()) {
            return 0.5; // Neutral score when no data
        }

        HostHeartbeat latest = heartbeats.get(0);
        Double cpu = latest.getCpuUsagePercent();
        if (cpu == null) {
            return 0.5;
        }
        return Math.max(0.0, 1.0 - (cpu / 100.0));
    }

    /**
     * Higher score for hosts with fewer existing replicas (promotes even distribution).
     */
    private double computeReplicaSpreadScore(Host host) {
        int replicaCount = chunkReplicaRepository.findByHostId(host.getId()).size();
        return 1.0 / (1.0 + replicaCount);
    }

    /**
     * Higher score for hosts with more recent heartbeats.
     * Normalised against the heartbeat timeout threshold.
     */
    private double computeHeartbeatRecencyScore(Host host, LocalDateTime now) {
        if (host.getLastHeartbeat() == null) {
            return 0.0;
        }
        long secondsSinceHeartbeat = Duration.between(host.getLastHeartbeat(), now).getSeconds();
        long timeoutSeconds = config.getHeartbeatTimeoutSeconds();

        if (secondsSinceHeartbeat >= timeoutSeconds) {
            return 0.0;
        }
        return 1.0 - ((double) secondsSinceHeartbeat / timeoutSeconds);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Filter helpers
    // ──────────────────────────────────────────────────────────────────────

    private boolean isHeartbeatRecent(Host host, LocalDateTime cutoff) {
        if (host.getLastHeartbeat() == null) {
            // Newly registered hosts that haven't sent a heartbeat yet — allow them
            // if they were created recently (within one heartbeat interval)
            return host.getCreatedAt() != null
                    && host.getCreatedAt().isAfter(cutoff);
        }
        return host.getLastHeartbeat().isAfter(cutoff);
    }

    private boolean hasActiveContainer(Host host) {
        return containerRepository.findByHostId(host.getId())
                .map(container -> container.getStatus() == StorageContainer.Status.ACTIVE)
                .orElse(false);
    }

    private long getAvailableCapacity(Host host) {
        return host.getTotalCapacityBytes()
                - host.getUsedCapacityBytes()
                - host.getReservedCapacityBytes();
    }

    /**
     * Internal record pairing a host with its computed score for sorting.
     */
    private record ScoredHost(Host host, double score) {
    }
}
