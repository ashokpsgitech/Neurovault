package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.ChunkReplica;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.replication.exception.InsufficientHostsException;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.HostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Load Balancing Service for the NeuroVault cluster.
 *
 * <p>Analyses the distribution of chunk replicas across hosts and performs
 * rebalancing when the standard deviation of replica counts exceeds a threshold.
 * Rebalancing moves replicas from overloaded hosts to underloaded hosts to
 * ensure even distribution of storage responsibility.</p>
 *
 * @author NeuroVault Team
 */
@Slf4j
@Service
public class LoadBalancingService {

    /** Rebalance trigger: if the coefficient of variation of replica counts exceeds this. */
    private static final double IMBALANCE_CV_THRESHOLD = 0.5;

    private final ReplicationService replicationService;
    private final HostSelectionService hostSelectionService;
    private final ChunkReplicaRepository chunkReplicaRepository;
    private final HostRepository hostRepository;
    private final ReplicationConfig config;

    public LoadBalancingService(ReplicationService replicationService,
                                HostSelectionService hostSelectionService,
                                ChunkReplicaRepository chunkReplicaRepository,
                                HostRepository hostRepository,
                                ReplicationConfig config) {
        this.replicationService = replicationService;
        this.hostSelectionService = hostSelectionService;
        this.chunkReplicaRepository = chunkReplicaRepository;
        this.hostRepository = hostRepository;
        this.config = config;
    }

    /**
     * Analyses the current replica distribution across all online hosts.
     *
     * @return map of hostId → replica count for online hosts
     */
    public Map<UUID, Integer> getLoadDistribution() {
        List<Host> onlineHosts = hostRepository.findByStatus(Host.Status.ONLINE);
        Map<UUID, Integer> distribution = new LinkedHashMap<>();

        for (Host host : onlineHosts) {
            int replicaCount = chunkReplicaRepository.findByHostId(host.getId()).stream()
                    .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                    .toList()
                    .size();
            distribution.put(host.getId(), replicaCount);
        }

        return distribution;
    }

    /**
     * Determines whether the cluster is imbalanced based on the coefficient
     * of variation (stddev / mean) of replica counts across online hosts.
     *
     * @return true if the cluster is imbalanced and rebalancing is recommended
     */
    public boolean analyzeDistribution() {
        Map<UUID, Integer> distribution = getLoadDistribution();

        if (distribution.size() <= 1) {
            return false; // Nothing to balance with 0 or 1 hosts
        }

        double mean = distribution.values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);

        if (mean == 0.0) {
            return false; // No replicas at all
        }

        double variance = distribution.values().stream()
                .mapToDouble(count -> Math.pow(count - mean, 2))
                .average()
                .orElse(0.0);

        double stddev = Math.sqrt(variance);
        double cv = stddev / mean; // Coefficient of variation

        log.info("Load distribution analysis: mean={:.1f}, stddev={:.2f}, cv={:.3f}, " +
                "threshold={}", mean, stddev, cv, IMBALANCE_CV_THRESHOLD);

        return cv > IMBALANCE_CV_THRESHOLD;
    }

    /**
     * Attempts to rebalance the cluster by migrating replicas from overloaded
     * hosts to underloaded hosts.
     *
     * <p>Strategy: identifies hosts above and below the mean replica count,
     * then moves replicas from the most-overloaded to the most-underloaded,
     * respecting the constraint that no two replicas of the same chunk can
     * exist on the same host.</p>
     *
     * @return number of replicas migrated
     */
    @Transactional
    public int rebalanceCluster() {
        Map<UUID, Integer> distribution = getLoadDistribution();

        if (distribution.size() <= 1) {
            log.info("Cannot rebalance with {} online hosts", distribution.size());
            return 0;
        }

        double mean = distribution.values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);

        int targetPerHost = (int) Math.ceil(mean);

        // Identify overloaded and underloaded hosts
        List<UUID> overloaded = distribution.entrySet().stream()
                .filter(e -> e.getValue() > targetPerHost)
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        List<UUID> underloaded = distribution.entrySet().stream()
                .filter(e -> e.getValue() < targetPerHost)
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (overloaded.isEmpty() || underloaded.isEmpty()) {
            log.info("No rebalancing needed — no overloaded/underloaded hosts");
            return 0;
        }

        log.info("Rebalancing: {} overloaded hosts, {} underloaded hosts, target={}",
                overloaded.size(), underloaded.size(), targetPerHost);

        int migrated = 0;
        int maxMigrations = config.getMaxConcurrentRepairs(); // Reuse repair limit

        for (UUID overloadedHostId : overloaded) {
            if (migrated >= maxMigrations) break;

            List<ChunkReplica> replicas = chunkReplicaRepository
                    .findByHostId(overloadedHostId).stream()
                    .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                    .collect(Collectors.toList());

            int excess = distribution.get(overloadedHostId) - targetPerHost;

            for (int i = 0; i < excess && migrated < maxMigrations; i++) {
                if (replicas.isEmpty() || underloaded.isEmpty()) break;

                ChunkReplica replicaToMove = replicas.remove(replicas.size() - 1);
                UUID chunkId = replicaToMove.getChunk().getId();

                // Get all hosts currently holding this chunk
                Set<UUID> currentChunkHosts = replicationService.getReplicasByChunk(chunkId)
                        .stream()
                        .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                        .map(r -> r.getHost().getId())
                        .collect(Collectors.toSet());

                // Find an underloaded host that doesn't already have this chunk
                UUID targetHostId = null;
                for (UUID candidateId : underloaded) {
                    if (!currentChunkHosts.contains(candidateId)) {
                        targetHostId = candidateId;
                        break;
                    }
                }

                if (targetHostId == null) {
                    continue; // No suitable target for this chunk
                }

                // Perform the migration: create new replica, then remove old one
                try {
                    replicationService.assignReplicas(chunkId, List.of(targetHostId));
                    replicationService.removeReplica(replicaToMove.getId());

                    // Update capacity accounting
                    long chunkSize = replicaToMove.getChunk().getSizeBytes();
                    hostRepository.findById(overloadedHostId).ifPresent(h -> {
                        h.setUsedCapacityBytes(Math.max(0,
                                h.getUsedCapacityBytes() - chunkSize));
                        hostRepository.save(h);
                    });
                    hostRepository.findById(targetHostId).ifPresent(h -> {
                        h.setUsedCapacityBytes(h.getUsedCapacityBytes() + chunkSize);
                        hostRepository.save(h);
                    });

                    // Update distribution tracking
                    distribution.merge(overloadedHostId, -1, Integer::sum);
                    distribution.merge(targetHostId, 1, Integer::sum);

                    migrated++;
                    log.debug("Migrated replica for chunk {} from host {} to host {}",
                            chunkId, overloadedHostId, targetHostId);

                } catch (Exception e) {
                    log.warn("Failed to migrate replica for chunk {}: {}",
                            chunkId, e.getMessage());
                }
            }
        }

        log.info("Rebalancing complete: {} replicas migrated", migrated);
        return migrated;
    }
}
