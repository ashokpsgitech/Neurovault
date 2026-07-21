package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.ChunkReplica;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.monitor.service.ClusterAnalyticsService;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.replication.dto.RepairResultDto;
import com.neurovault.backend.replication.event.ClusterEventPublisher;
import com.neurovault.backend.replication.event.ClusterEventType;
import com.neurovault.backend.replication.exception.InsufficientHostsException;
import com.neurovault.backend.repository.HostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core Self-Healing Engine for NeuroVault.
 *
 * <p>Implements the automatic replica recovery workflow:</p>
 * <ol>
 *   <li>Detect under-replicated chunks</li>
 *   <li>For each under-replicated chunk:
 *     <ol>
 *       <li>Locate surviving replicas</li>
 *       <li>Determine replica deficit</li>
 *       <li>Find replacement hosts via {@link HostSelectionService}</li>
 *       <li>Create new replica metadata via {@link ReplicationService}</li>
 *       <li>Update host capacity</li>
 *       <li>Publish recovery events</li>
 *       <li>Increment repair/recovery counters</li>
 *     </ol>
 *   </li>
 *   <li>Continue monitoring</li>
 * </ol>
 *
 * <p><strong>Note:</strong> This engine operates at the metadata level. Actual
 * byte-level data transfer between hosts will be handled by the Data Plane
 * integration (Member 2's storage engine) via a future {@code ReplicationTransferService}
 * interface.</p>
 *
 * @author NeuroVault Team
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

    public SelfHealingService(ReplicationService replicationService,
                              HostSelectionService hostSelectionService,
                              ClusterEventPublisher eventPublisher,
                              ClusterAnalyticsService analyticsService,
                              ReplicationConfig config,
                              HostRepository hostRepository) {
        this.replicationService = replicationService;
        this.hostSelectionService = hostSelectionService;
        this.eventPublisher = eventPublisher;
        this.analyticsService = analyticsService;
        this.config = config;
        this.hostRepository = hostRepository;
    }

    /**
     * Runs a full self-healing cycle: scans for under-replicated chunks and
     * initiates repairs up to {@code maxConcurrentRepairs}.
     *
     * @return repair result summary
     */
    @Transactional
    public RepairResultDto runHealingCycle() {
        log.info("═══ Starting self-healing cycle ═══");

        Map<UUID, Integer> underReplicated = replicationService.getUnderReplicatedChunks();
        int chunksInspected = underReplicated.size();
        int repairsInitiated = 0;
        int repairsSucceeded = 0;
        int repairsFailed = 0;
        List<String> details = new ArrayList<>();

        if (underReplicated.isEmpty()) {
            log.info("All chunks are fully replicated — no repairs needed");
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
                            "(insufficient hosts)", chunkId, deficit - repaired));
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
     * Heals a single under-replicated chunk by placing new replicas on selected hosts.
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

        // Get current replica hosts to exclude
        List<ChunkReplica> existingReplicas = replicationService.getReplicasByChunk(chunkId);
        Set<UUID> currentHostIds = existingReplicas.stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                .map(r -> r.getHost().getId())
                .collect(Collectors.toSet());

        int repaired = 0;

        for (int i = 0; i < deficit; i++) {
            try {
                Host replacementHost = hostSelectionService
                        .selectReplacementHost(chunkId, currentHostIds);

                // Create the new replica
                replicationService.assignReplicas(chunkId,
                        List.of(replacementHost.getId()));

                // Update host used capacity (metadata-level accounting)
                long chunkSize = existingReplicas.isEmpty() ? 0
                        : existingReplicas.get(0).getChunk().getSizeBytes();
                replacementHost.setUsedCapacityBytes(
                        replacementHost.getUsedCapacityBytes() + chunkSize);
                hostRepository.save(replacementHost);

                // Add to exclusion set for the next iteration
                currentHostIds.add(replacementHost.getId());

                eventPublisher.publish(this, ClusterEventType.REPLICA_RESTORED,
                        replacementHost.getId(), chunkId,
                        String.format("Replica restored on host %s (%s)",
                                replacementHost.getName(), replacementHost.getId()));
                analyticsService.incrementRecoveryCount();
                repaired++;

                log.info("  → Replica {}/{} created on host {} for chunk {}",
                        repaired, deficit, replacementHost.getName(), chunkId);

            } catch (InsufficientHostsException e) {
                log.warn("  → No more eligible hosts for chunk {} (placed {}/{})",
                        chunkId, repaired, deficit);
                break;
            }
        }

        if (repaired == deficit) {
            eventPublisher.publishChunkEvent(this, ClusterEventType.REPAIR_COMPLETED,
                    chunkId, "All " + deficit + " replicas restored");
        }

        return repaired;
    }
}
