package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.Chunk;
import com.neurovault.backend.entity.ChunkReplica;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.monitor.service.ClusterAnalyticsService;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.replication.dto.RepairResultDto;
import com.neurovault.backend.replication.event.ClusterEventPublisher;
import com.neurovault.backend.replication.event.ClusterEventType;
import com.neurovault.backend.replication.exception.InsufficientHostsException;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.storage.dto.ChunkTransferResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Core Self-Healing Engine for NeuroVault.
 *
 * <p>Implements the complete automatic replica recovery workflow with physical
 * data transfer between storage hosts:</p>
 * <ol>
 *   <li>Detect under-replicated chunks</li>
 *   <li>For each under-replicated chunk:
 *     <ol>
 *       <li>Find a healthy source host with an ACTIVE replica</li>
 *       <li>Select a replacement destination host</li>
 *       <li>Create a SYNCING replica record</li>
 *       <li>Execute physical chunk transfer (source → destination)</li>
 *       <li>Verify data integrity at destination</li>
 *       <li>Promote replica to ACTIVE on success</li>
 *       <li>Mark replica MISSING on failure (retryable next cycle)</li>
 *     </ol>
 *   </li>
 * </ol>
 *
 * <p><strong>Architecture Rule:</strong> The Coordinator (this service) NEVER proxies
 * chunk bytes. It issues transfer commands via {@link ChunkTransferService}, which
 * orchestrates source → destination data flow.</p>
 *
 * <p><strong>Concurrency:</strong> An {@link AtomicBoolean} guard prevents overlapping
 * healing cycles from concurrent schedulers ({@code ClusterMaintenanceScheduler} at 30s
 * and {@code HeartbeatScheduler} at 60s).</p>
 *
 * @author NeuroVault Team
 */
@Slf4j
@Service
public class SelfHealingService {

    private final ReplicationService replicationService;
    private final HostSelectionService hostSelectionService;
    private final ChunkTransferService chunkTransferService;
    private final ClusterEventPublisher eventPublisher;
    private final ClusterAnalyticsService analyticsService;
    private final ReplicationConfig config;
    private final HostRepository hostRepository;
    private final ChunkReplicaRepository chunkReplicaRepository;

    /** Concurrency guard — prevents overlapping healing cycles. */
    private final AtomicBoolean healingInProgress = new AtomicBoolean(false);

    public SelfHealingService(ReplicationService replicationService,
                              HostSelectionService hostSelectionService,
                              ChunkTransferService chunkTransferService,
                              ClusterEventPublisher eventPublisher,
                              ClusterAnalyticsService analyticsService,
                              ReplicationConfig config,
                              HostRepository hostRepository,
                              ChunkReplicaRepository chunkReplicaRepository) {
        this.replicationService = replicationService;
        this.hostSelectionService = hostSelectionService;
        this.chunkTransferService = chunkTransferService;
        this.eventPublisher = eventPublisher;
        this.analyticsService = analyticsService;
        this.config = config;
        this.hostRepository = hostRepository;
        this.chunkReplicaRepository = chunkReplicaRepository;
    }

    /**
     * Runs a full self-healing cycle: cleans up stale SYNCING replicas, scans for
     * under-replicated chunks, and initiates physical repairs up to
     * {@code maxConcurrentRepairs}.
     *
     * <p>Thread-safe: only one cycle can run at a time via {@code healingInProgress}.</p>
     *
     * @return repair result summary
     */
    @Transactional
    public RepairResultDto runHealingCycle() {
        if (!healingInProgress.compareAndSet(false, true)) {
            log.info("Self-healing cycle already in progress — skipping");
            return RepairResultDto.builder()
                    .chunksInspected(0)
                    .repairsInitiated(0)
                    .repairsSucceeded(0)
                    .repairsFailed(0)
                    .details(List.of("Skipped: healing cycle already in progress"))
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        try {
            log.info("═══ Starting self-healing cycle ═══");
            eventPublisher.publishChunkEvent(this, ClusterEventType.HEALING_CYCLE_STARTED,
                    null, "Self-healing cycle started");

            // Step 0: Clean up stale SYNCING replicas from previous failed cycles
            cleanupStaleSyncingReplicas();

            // Step 1: Find under-replicated chunks
            Map<UUID, Integer> underReplicated = replicationService.getUnderReplicatedChunks();
            int chunksInspected = underReplicated.size();
            int repairsInitiated = 0;
            int repairsSucceeded = 0;
            int repairsFailed = 0;
            List<String> details = new ArrayList<>();

            if (underReplicated.isEmpty()) {
                log.info("All chunks are fully replicated — no repairs needed");
                eventPublisher.publishChunkEvent(this, ClusterEventType.HEALING_CYCLE_COMPLETED,
                        null, "No repairs needed — all chunks fully replicated");
                return RepairResultDto.builder()
                        .chunksInspected(0)
                        .repairsInitiated(0)
                        .repairsSucceeded(0)
                        .repairsFailed(0)
                        .details(List.of("All chunks fully replicated"))
                        .timestamp(LocalDateTime.now())
                        .build();
            }

            log.warn("Found {} under-replicated chunks — initiating repairs", chunksInspected);

            // Step 2: Repair each under-replicated chunk
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
                        details.add(String.format("Chunk %s: %d replicas could not be restored",
                                chunkId, deficit - repaired));
                    }
                } catch (Exception e) {
                    log.error("Failed to heal chunk {}: {}", chunkId, e.getMessage(), e);
                    repairsInitiated++;
                    repairsFailed++;
                    details.add(String.format("Chunk %s: repair failed — %s",
                            chunkId, e.getMessage()));

                    eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_FAILED,
                            chunkId, "Repair failed: " + e.getMessage());
                }
            }

            log.info("═══ Self-healing cycle complete: inspected={}, initiated={}, " +
                            "succeeded={}, failed={} ═══",
                    chunksInspected, repairsInitiated, repairsSucceeded, repairsFailed);

            eventPublisher.publishChunkEvent(this, ClusterEventType.HEALING_CYCLE_COMPLETED,
                    null, String.format("Cycle complete: %d inspected, %d succeeded, %d failed",
                            chunksInspected, repairsSucceeded, repairsFailed));

            return RepairResultDto.builder()
                    .chunksInspected(chunksInspected)
                    .repairsInitiated(repairsInitiated)
                    .repairsSucceeded(repairsSucceeded)
                    .repairsFailed(repairsFailed)
                    .details(details)
                    .timestamp(LocalDateTime.now())
                    .build();

        } finally {
            healingInProgress.set(false);
        }
    }

    /**
     * Heals a single under-replicated chunk by performing physical data transfers.
     *
     * <p>For each missing replica:
     * <ol>
     *   <li>Find a healthy source host with an ACTIVE replica</li>
     *   <li>Select a destination host via {@link HostSelectionService}</li>
     *   <li>Create a SYNCING replica record</li>
     *   <li>Execute physical transfer via {@link ChunkTransferService}</li>
     *   <li>Verify integrity and promote to ACTIVE, or mark MISSING on failure</li>
     * </ol>
     *
     * @param chunkId the chunk to heal
     * @param deficit the number of additional replicas needed
     * @return number of replicas successfully created
     */
    @Transactional
    public int healChunk(UUID chunkId, int deficit) {
        log.info("Healing chunk {} — deficit: {}", chunkId, deficit);

        eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_INITIATED,
                chunkId, "Initiating repair for deficit of " + deficit);
        analyticsService.incrementRepairCount();

        // Build exclusion set from existing replicas (any status)
        List<ChunkReplica> existingReplicas = replicationService.getReplicasByChunk(chunkId);
        Set<UUID> currentHostIds = existingReplicas.stream()
                .map(r -> r.getHost().getId())
                .collect(Collectors.toSet());

        // Get chunk metadata for owner and checksum info
        Chunk chunk = existingReplicas.isEmpty() ? null : existingReplicas.get(0).getChunk();
        String expectedChecksum = chunk != null ? chunk.getChecksum() : null;
        UUID ownerId = (chunk != null && chunk.getFile() != null && chunk.getFile().getOwner() != null)
                ? chunk.getFile().getOwner().getId() : null;

        int repaired = 0;

        for (int i = 0; i < deficit; i++) {
            try {
                // 1. Find a healthy source host
                Optional<Host> sourceHostOpt = replicationService.findHealthySourceHost(chunkId);
                if (sourceHostOpt.isEmpty()) {
                    log.error("  → No healthy source available for chunk {} — data may be unrecoverable",
                            chunkId);
                    eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_NO_SOURCE_AVAILABLE,
                            chunkId, "No healthy source host has an ACTIVE replica");
                    break;
                }
                Host sourceHost = sourceHostOpt.get();

                // 2. Select a destination host
                Host destinationHost;
                try {
                    destinationHost = hostSelectionService.selectReplacementHost(chunkId, currentHostIds);
                } catch (InsufficientHostsException e) {
                    log.warn("  → No eligible destination for chunk {} (placed {}/{})",
                            chunkId, repaired, deficit);
                    eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_NO_DESTINATION_AVAILABLE,
                            chunkId, "No eligible destination host available");
                    break;
                }

                // 3. Create SYNCING replica (metadata placeholder)
                ChunkReplica syncingReplica;
                try {
                    syncingReplica = replicationService.createSyncingReplica(chunkId, destinationHost.getId());
                } catch (Exception e) {
                    log.warn("  → Could not create SYNCING replica for chunk {} on host {}: {}",
                            chunkId, destinationHost.getName(), e.getMessage());
                    currentHostIds.add(destinationHost.getId());
                    continue;
                }

                log.info("  → Repair {}/{}: source={}, destination={}, replica={}",
                        i + 1, deficit, sourceHost.getName(), destinationHost.getName(),
                        syncingReplica.getId());

                eventPublisher.publish(this, ClusterEventType.REPAIR_TRANSFER_STARTED,
                        destinationHost.getId(), chunkId,
                        String.format("Transfer started: %s → %s", sourceHost.getName(), destinationHost.getName()));

                // 4. Execute physical transfer
                ChunkTransferResponse transferResult = chunkTransferService.transferChunk(
                        sourceHost, destinationHost, chunkId, expectedChecksum, ownerId);

                if (transferResult.isSuccess()) {
                    // 5a. Transfer succeeded — verify and promote
                    eventPublisher.publish(this, ClusterEventType.REPAIR_TRANSFER_COMPLETED,
                            destinationHost.getId(), chunkId,
                            "Transfer completed successfully");

                    // Verify integrity
                    boolean integrityOk = expectedChecksum == null
                            || expectedChecksum.isEmpty()
                            || expectedChecksum.equalsIgnoreCase(transferResult.getDestinationChecksum());

                    if (integrityOk) {
                        eventPublisher.publish(this, ClusterEventType.REPAIR_INTEGRITY_VERIFIED,
                                destinationHost.getId(), chunkId,
                                "Integrity verified: " + transferResult.getDestinationChecksum());

                        // 6. Promote replica to ACTIVE
                        replicationService.markReplicaActive(syncingReplica.getId());

                        // Update host capacity
                        long chunkSize = chunk != null ? chunk.getSizeBytes() : 0;
                        destinationHost.setUsedCapacityBytes(
                                destinationHost.getUsedCapacityBytes() + chunkSize);
                        hostRepository.save(destinationHost);

                        eventPublisher.publish(this, ClusterEventType.REPAIR_METADATA_COMMITTED,
                                destinationHost.getId(), chunkId,
                                String.format("Replica %s committed as ACTIVE on host %s",
                                        syncingReplica.getId(), destinationHost.getName()));

                        eventPublisher.publish(this, ClusterEventType.REPLICA_RESTORED,
                                destinationHost.getId(), chunkId,
                                String.format("Replica restored on host %s (%s)",
                                        destinationHost.getName(), destinationHost.getId()));
                        analyticsService.incrementRecoveryCount();
                        repaired++;

                        log.info("  ✓ Replica {}/{} created on host {} for chunk {}",
                                repaired, deficit, destinationHost.getName(), chunkId);
                    } else {
                        // Integrity mismatch after transfer
                        log.error("  ✗ Integrity mismatch for chunk {} on host {}: expected={}, got={}",
                                chunkId, destinationHost.getName(),
                                expectedChecksum, transferResult.getDestinationChecksum());
                        syncingReplica.setStatus(ChunkReplica.Status.MISSING);
                        chunkReplicaRepository.save(syncingReplica);
                    }
                } else {
                    // 5b. Transfer failed — mark replica MISSING for retry
                    log.error("  ✗ Transfer failed for chunk {} → {}: {}",
                            chunkId, destinationHost.getName(), transferResult.getErrorMessage());
                    syncingReplica.setStatus(ChunkReplica.Status.MISSING);
                    chunkReplicaRepository.save(syncingReplica);

                    eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_FAILED,
                            chunkId, "Transfer failed: " + transferResult.getErrorMessage());
                }

                // Add to exclusion set for next iteration
                currentHostIds.add(destinationHost.getId());

            } catch (Exception e) {
                log.error("  ✗ Unexpected error healing chunk {} (iteration {}): {}",
                        chunkId, i + 1, e.getMessage(), e);
                eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_FAILED,
                        chunkId, "Unexpected error: " + e.getMessage());
            }
        }

        if (repaired == deficit) {
            eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_COMPLETED,
                    chunkId, "All " + deficit + " replicas restored");
        }

        return repaired;
    }

    /**
     * Cleans up stale SYNCING replicas from previous failed healing cycles.
     * Replicas stuck in SYNCING state longer than {@code transferTimeoutSeconds}
     * are marked MISSING so they become eligible for repair in the next cycle.
     */
    private void cleanupStaleSyncingReplicas() {
        List<ChunkReplica> syncingReplicas = chunkReplicaRepository
                .findByStatus(ChunkReplica.Status.SYNCING);

        if (syncingReplicas.isEmpty()) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now()
                .minusSeconds(config.getTransferTimeoutSeconds());
        int cleaned = 0;

        for (ChunkReplica replica : syncingReplicas) {
            if (replica.getCreatedAt().isBefore(cutoff)) {
                log.warn("Cleaning up stale SYNCING replica {} (chunk={}, host={}, created={})",
                        replica.getId(), replica.getChunk().getId(),
                        replica.getHost().getId(), replica.getCreatedAt());
                replica.setStatus(ChunkReplica.Status.MISSING);
                chunkReplicaRepository.save(replica);
                cleaned++;
            }
        }

        if (cleaned > 0) {
            log.info("Cleaned up {} stale SYNCING replicas", cleaned);
        }
    }
}
