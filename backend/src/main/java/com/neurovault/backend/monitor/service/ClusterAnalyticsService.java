package com.neurovault.backend.monitor.service;

import com.neurovault.backend.entity.ChunkReplica;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.monitor.dto.ClusterStatistics;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.ChunkRepository;
import com.neurovault.backend.repository.FileMetadataRepository;
import com.neurovault.backend.repository.HostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for computing and caching cluster-wide analytics.
 *
 * <p>Statistics are cached with a configurable TTL (default 10 seconds)
 * to avoid hammering the database on every API call. Repair and recovery
 * counters are maintained as atomic in-memory counters, reset on application restart.</p>
 *
 * @author NeuroVault Team
 */
@Slf4j
@Service
public class ClusterAnalyticsService {

    private static final long CACHE_TTL_MS = 10_000; // 10 seconds

    private final HostRepository hostRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkReplicaRepository chunkReplicaRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final ReplicationConfig config;

    /** Atomic counter for repair operations initiated. */
    private final AtomicLong repairCounter = new AtomicLong(0);

    /** Atomic counter for successful recovery operations. */
    private final AtomicLong recoveryCounter = new AtomicLong(0);

    /** Cached statistics. */
    private volatile ClusterStatistics cachedStatistics;

    /** Timestamp when the cache was last refreshed. */
    private volatile long cacheTimestamp = 0;

    public ClusterAnalyticsService(HostRepository hostRepository,
                                   ChunkRepository chunkRepository,
                                   ChunkReplicaRepository chunkReplicaRepository,
                                   FileMetadataRepository fileMetadataRepository,
                                   ReplicationConfig config) {
        this.hostRepository = hostRepository;
        this.chunkRepository = chunkRepository;
        this.chunkReplicaRepository = chunkReplicaRepository;
        this.fileMetadataRepository = fileMetadataRepository;
        this.config = config;
    }

    /**
     * Computes or returns cached cluster statistics.
     *
     * @return current cluster statistics
     */
    public ClusterStatistics computeStatistics() {
        long now = System.currentTimeMillis();
        if (cachedStatistics != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedStatistics;
        }

        log.debug("Computing cluster statistics...");

        List<Host> allHosts = hostRepository.findAll();
        List<ChunkReplica> allReplicas = chunkReplicaRepository.findAll();

        int totalHosts = allHosts.size();
        int onlineHosts = (int) allHosts.stream()
                .filter(h -> h.getStatus() == Host.Status.ONLINE)
                .count();
        int offlineHosts = totalHosts - onlineHosts;

        long totalStorage = allHosts.stream()
                .mapToLong(Host::getTotalCapacityBytes)
                .sum();
        long usedStorage = allHosts.stream()
                .mapToLong(Host::getUsedCapacityBytes)
                .sum();
        long availableStorage = allHosts.stream()
                .mapToLong(h -> Math.max(0,
                        h.getTotalCapacityBytes() - h.getUsedCapacityBytes() - h.getReservedCapacityBytes()))
                .sum();

        long totalFiles = fileMetadataRepository.count();
        long totalChunks = chunkRepository.count();
        long totalReplicaCount = allReplicas.size();

        long activeReplicas = allReplicas.stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                .count();
        long missingReplicas = allReplicas.stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.MISSING)
                .count();
        long corruptedReplicas = allReplicas.stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.CORRUPTED)
                .count();

        double avgReplicationFactor = totalChunks > 0
                ? (double) activeReplicas / totalChunks
                : 0.0;
        double utilization = totalStorage > 0
                ? (double) usedStorage / totalStorage * 100.0
                : 0.0;

        ClusterStatistics stats = ClusterStatistics.builder()
                .totalHosts(totalHosts)
                .onlineHosts(onlineHosts)
                .offlineHosts(offlineHosts)
                .totalStorageBytes(totalStorage)
                .usedStorageBytes(usedStorage)
                .availableStorageBytes(availableStorage)
                .totalFiles(totalFiles)
                .totalChunks(totalChunks)
                .totalReplicas(totalReplicaCount)
                .activeReplicas(activeReplicas)
                .missingReplicas(missingReplicas)
                .corruptedReplicas(corruptedReplicas)
                .repairCount(repairCounter.get())
                .recoveryCount(recoveryCounter.get())
                .averageReplicationFactor(avgReplicationFactor)
                .clusterUtilizationPercent(utilization)
                .timestamp(LocalDateTime.now())
                .build();

        cachedStatistics = stats;
        cacheTimestamp = now;

        log.info("Cluster stats: hosts={}/{} online, files={}, chunks={}, replicas={}/{} active, "
                        + "avg-rf={:.2f}, utilization={:.1f}%",
                onlineHosts, totalHosts, totalFiles, totalChunks,
                activeReplicas, totalReplicaCount, avgReplicationFactor, utilization);

        return stats;
    }

    /**
     * Forces a fresh computation of statistics, bypassing the cache.
     */
    public ClusterStatistics forceRefresh() {
        cacheTimestamp = 0;
        return computeStatistics();
    }

    /**
     * Returns the average replication factor across all chunks.
     */
    public double getAverageReplicationFactor() {
        return computeStatistics().getAverageReplicationFactor();
    }

    /**
     * Increments the repair counter (called by the self-healing engine).
     */
    public void incrementRepairCount() {
        repairCounter.incrementAndGet();
    }

    /**
     * Increments the recovery counter (called on successful repair).
     */
    public void incrementRecoveryCount() {
        recoveryCounter.incrementAndGet();
    }

    /**
     * Returns the current repair count.
     */
    public long getRepairCount() {
        return repairCounter.get();
    }

    /**
     * Returns the current recovery count.
     */
    public long getRecoveryCount() {
        return recoveryCounter.get();
    }

    /**
     * Invalidates the statistics cache.
     */
    public void invalidateCache() {
        cacheTimestamp = 0;
        cachedStatistics = null;
    }
}
