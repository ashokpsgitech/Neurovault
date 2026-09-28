package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.ChunkReplica;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.ReplicationTask;
import com.neurovault.backend.monitor.service.ClusterAnalyticsService;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.replication.dto.RepairResultDto;
import com.neurovault.backend.replication.event.ClusterEventPublisher;
import com.neurovault.backend.replication.event.ClusterEventType;
import com.neurovault.backend.replication.exception.InsufficientHostsException;
import com.neurovault.backend.replication.exception.ReplicationException;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.ReplicationTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core Self-Healing Engine for NeuroVault.
 *
 * <p>Core Invariant: Replicas are NEVER marked ACTIVE without verified physical bytes.
 * There is NO fallback path that creates metadata-only ACTIVE replicas. If no ACTIVE
 * source replica exists, the chunk data is permanently lost and this service refuses
 * to create phantom replica records.
 */
@Slf4j
@Service
public class SelfHealingService {

    private final ReplicationService replicationService;
    private final HostSelectionService hostSelectionService;
    private final ClusterEventPublisher eventPublisher;
    private final ClusterAnalyticsService analyticsService;
    private final ReplicationConfig config;
    private final HostRepository hostRepository;
    private final ReplicationTaskRepository taskRepository;
    private final ReplicationTransferService replicationTransferService;

    /** Stable worker identity across healing cycles (NV-P1-05). */
    private final String stableWorkerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public SelfHealingService(ReplicationService replicationService,
                              HostSelectionService hostSelectionService,
                              ClusterEventPublisher eventPublisher,
                              ClusterAnalyticsService analyticsService,
                              ReplicationConfig config,
                              HostRepository hostRepository,
                              ReplicationTaskRepository taskRepository,
                              ReplicationTransferService replicationTransferService) {
        this.replicationService = replicationService;
        this.hostSelectionService = hostSelectionService;
        this.eventPublisher = eventPublisher;
        this.analyticsService = analyticsService;
        this.config = config;
        this.hostRepository = hostRepository;
        this.taskRepository = taskRepository;
        this.replicationTransferService = replicationTransferService;
    }

    /**
     * Runs a full self-healing cycle: scans for under-replicated chunks and
     * initiates repairs up to maxConcurrentRepairs.
     */
    @Transactional
    public RepairResultDto runHealingCycle() {
        log.info("Starting self-healing cycle");

        Map<UUID, Integer> underReplicated = replicationService.getUnderReplicatedChunks();
        int chunksInspected = underReplicated.size();
        int repairsInitiated = 0;
        int repairsSucceeded = 0;
        int repairsFailed = 0;
        List<String> details = new ArrayList<>();

        if (underReplicated.isEmpty()) {
            log.info("All chunks are fully replicated - no repairs needed");
            return RepairResultDto.builder()
                    .chunksInspected(0)
                    .repairsInitiated(0)
                    .repairsSucceeded(0)
                    .repairsFailed(0)
                    .details(List.of("All chunks fully replicated"))
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        log.warn("Found {} under-replicated chunks - initiating physical repairs", chunksInspected);

        for (Map.Entry<UUID, Integer> entry : underReplicated.entrySet()) {
            if (repairsInitiated >= config.getMaxConcurrentRepairs()) {
                log.info("Reached max concurrent repairs ({}), deferring remaining",
                        config.getMaxConcurrentRepairs());
                details.add("Deferred repairs: max concurrent limit reached (" +
                        config.getMaxConcurrentRepairs() + ")");
                break;
            }

            UUID chunkId = entry.getKey();
            int deficit = entry.getValue();

            try {
                int repaired = healChunk(chunkId, deficit);
                repairsInitiated += deficit;
                repairsSucceeded += repaired;
                repairsFailed += (deficit - repaired);

                if (repaired > 0) {
                    details.add(String.format("Chunk %s: restored %d/%d replicas",
                            chunkId, repaired, deficit));
                }
                if (repaired < deficit) {
                    details.add(String.format("Chunk %s: %d replicas could not be restored " +
                            "(insufficient hosts or no source)", chunkId, deficit - repaired));
                }
            } catch (Exception e) {
                log.error("Failed to heal chunk {}: {}", chunkId, e.getMessage(), e);
                repairsInitiated++;
                repairsFailed++;
                details.add(String.format("Chunk %s: repair failed - %s",
                        chunkId, e.getMessage()));
                eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_FAILED,
                        chunkId, "Repair failed: " + e.getMessage());
            }
        }

        log.info("Self-healing cycle complete: inspected={}, initiated={}, succeeded={}, failed={}",
                chunksInspected, repairsInitiated, repairsSucceeded, repairsFailed);

        return RepairResultDto.builder()
                .chunksInspected(chunksInspected)
                .repairsInitiated(repairsInitiated)
                .repairsSucceeded(repairsSucceeded)
                .repairsFailed(repairsFailed)
                .details(details)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Heals a single under-replicated chunk by physically replicating it onto selected hosts.
     *
     * <p>NV-P0-01 Fix: There is NO fallback path that creates metadata-only ACTIVE replicas.
     * The only way a replica becomes ACTIVE is via ReplicationTransferService.copyChunk(),
     * which enforces the full verified lifecycle:
     * PLANNED -> RESERVED -> TRANSFERRING -> VERIFIED -> ACTIVE
     *
     * <p>If no ACTIVE source replica exists (data permanently lost), this method returns 0
     * immediately without creating any phantom replica records.
     *
     * @return number of replicas actually restored with verified physical bytes
     */
    @Transactional
    public int healChunk(UUID chunkId, int deficit) {
        log.info("Healing chunk {} - deficit: {}", chunkId, deficit);

        eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_INITIATED,
                chunkId, "Initiating repair for deficit of " + deficit);
        analyticsService.incrementRepairCount();

        List<ChunkReplica> existingReplicas = replicationService.getReplicasByChunk(chunkId);
        Set<UUID> currentHostIds = existingReplicas.stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                .map(r -> r.getHost().getId())
                .collect(Collectors.toSet());

        UUID sourceHostId = existingReplicas.stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                .map(r -> r.getHost().getId())
                .findFirst()
                .orElse(null);

        // Invariant: NEVER heal without a surviving physical source.
        // If no ACTIVE replica exists, data is permanently lost.
        // Previously (NV-P0-01) this fell back to assignReplicas() creating phantom ACTIVE replicas.
        // That fallback is now permanently removed.
        if (sourceHostId == null) {
            log.error("UNRECOVERABLE: Chunk {} has zero ACTIVE source replicas. " +
                    "Physical bytes are gone - refusing to create phantom replicas.", chunkId);
            eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_FAILED,
                    chunkId, "Unrecoverable: no surviving source replica - data may be permanently lost");
            return 0;
        }

        int repaired = 0;
        String workerId = this.stableWorkerId;

        for (int i = 0; i < deficit; i++) {
            Host replacementHost = null;
            ReplicationTask task = null;
            try {
                replacementHost = hostSelectionService.selectReplacementHost(chunkId, currentHostIds);

                task = ReplicationTask.builder()
                        .chunkId(chunkId)
                        .sourceHostId(sourceHostId)
                        .targetHostId(replacementHost.getId())
                        .status(ReplicationTask.Status.PENDING)
                        .reason("Automatic physical self-healing deficit recovery")
                        .workerId(workerId)
                        .attemptCount(0)
                        .maxAttempts(3)
                        .leaseExpiresAt(LocalDateTime.now().plusMinutes(5))
                        .build();
                task = taskRepository.save(task);

                int claimed = taskRepository.claimTaskAtomic(
                        task.getId(), workerId, LocalDateTime.now().plusMinutes(5), LocalDateTime.now());

                if (claimed > 0) {
                    taskRepository.markRunning(task.getId(), workerId, LocalDateTime.now());

                    // Physical streaming transfer - MUST succeed before replica is marked ACTIVE.
                    // ReplicationTransferService enforces PLANNED->RESERVED->TRANSFERRING->VERIFIED->ACTIVE.
                    // There is NO fallback that bypasses physical verification.
                    replicationTransferService.copyChunk(sourceHostId, replacementHost.getId(), chunkId, null);

                    taskRepository.markSucceeded(task.getId(), workerId, LocalDateTime.now());
                    task.setStatus(ReplicationTask.Status.SUCCEEDED);
                    task.setCompletedAt(LocalDateTime.now());
                    taskRepository.save(task);
                } else {
                    log.warn("Task {} already claimed by another worker, skipping", task.getId());
                    continue;
                }

                currentHostIds.add(replacementHost.getId());

                eventPublisher.publish(this, ClusterEventType.REPLICA_RESTORED,
                        replacementHost.getId(), chunkId,
                        String.format("Replica physically restored and verified on host %s (%s)",
                                replacementHost.getName(), replacementHost.getId()));
                analyticsService.incrementRecoveryCount();
                repaired++;

                log.info("  -> Replica {}/{} physically created and verified on host {} for chunk {} (task={})",
                        repaired, deficit, replacementHost.getName(), chunkId, task.getId());

            } catch (InsufficientHostsException e) {
                log.warn("  -> No more eligible hosts for chunk {} (placed {}/{})",
                        chunkId, repaired, deficit);
                break;
            } catch (Exception e) {
                log.error("Failed to physically replicate chunk {} to host {}: {}",
                        chunkId, replacementHost != null ? replacementHost.getId() : "unknown",
                        e.getMessage(), e);
                if (task != null) {
                    taskRepository.markFailed(task.getId(), workerId, e.getMessage(), LocalDateTime.now());
                }
            }
        }

        if (repaired == deficit) {
            eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_COMPLETED,
                    chunkId, "All " + deficit + " replicas physically restored and verified");
        }

        return repaired;
    }
}
