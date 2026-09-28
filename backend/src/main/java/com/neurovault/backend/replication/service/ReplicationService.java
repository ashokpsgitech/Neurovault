package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.Chunk;
import com.neurovault.backend.entity.ChunkReplica;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.replication.dto.ReplicaInfoDto;
import com.neurovault.backend.replication.exception.ReplicationException;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.ChunkRepository;
import com.neurovault.backend.repository.HostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core Replication Manager responsible for maintaining chunk replica metadata.
 *
 * <p>This service operates at the <strong>metadata level</strong> — it creates and manages
 * {@link ChunkReplica} records but does NOT perform actual byte-level data transfer
 * (which is the Data Plane's responsibility via {@code ReplicationTransferService}).
 *
 * <p><strong>NV-P0-02 Fix:</strong> {@link #assignReplicas} now creates replicas in
 * {@code PLANNED} status only. Replicas can only be promoted to {@code ACTIVE} by
 * {@code ReplicationTransferService.copyChunk()} after verified physical byte transfer.
 * Callers that previously passed hostIds expecting instant ACTIVE replicas must now
 * invoke the data-plane transfer service.
 */
@Slf4j
@Service
public class ReplicationService {

    private final ChunkReplicaRepository chunkReplicaRepository;
    private final ChunkRepository chunkRepository;
    private final HostRepository hostRepository;
    private final ReplicationConfig config;

    public ReplicationService(ChunkReplicaRepository chunkReplicaRepository,
                              ChunkRepository chunkRepository,
                              HostRepository hostRepository,
                              ReplicationConfig config) {
        this.chunkReplicaRepository = chunkReplicaRepository;
        this.chunkRepository = chunkRepository;
        this.hostRepository = hostRepository;
        this.config = config;
    }

    /**
     * Assigns replica placement intent for a chunk on the given hosts.
     *
     * <p><strong>Important:</strong> This method creates replicas in {@code PLANNED} status.
     * Replicas are NOT usable for reads until physical bytes are transferred and verified
     * by the data plane, which will promote status to {@code ACTIVE}.
     *
     * <p>This method intentionally cannot create {@code ACTIVE} replicas. The only path
     * to {@code ACTIVE} is {@code ReplicationTransferService.copyChunk()} which enforces
     * physical byte verification before promotion.
     *
     * @param chunkId the chunk to replicate
     * @param hostIds the hosts on which to place replicas
     * @return list of created PLANNED replica records
     * @throws ReplicationException if the chunk or any host is not found
     */
    @Transactional
    public List<ChunkReplica> assignReplicas(UUID chunkId, List<UUID> hostIds) {
        log.info("Assigning {} PLANNED replica slots for chunk {}", hostIds.size(), chunkId);

        Chunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new ReplicationException("Chunk not found: " + chunkId));

        List<ChunkReplica> existingReplicas = chunkReplicaRepository.findByChunkId(chunkId);
        List<ChunkReplica> replicas = new ArrayList<>();

        for (UUID hostId : hostIds) {
            Host host = hostRepository.findById(hostId)
                    .orElseThrow(() -> new ReplicationException("Host not found: " + hostId));

            // Check for existing replica on this host
            Optional<ChunkReplica> existing = existingReplicas.stream()
                    .filter(r -> r.getHost().getId().equals(hostId))
                    .findFirst();

            if (existing.isPresent()) {
                ChunkReplica existingReplica = existing.get();
                // Return existing replica regardless of status — do NOT promote to ACTIVE here.
                // Physical verification by ReplicationTransferService is required for promotion.
                log.info("Replica already exists for chunk {} on host {} with status {} — returning existing",
                        chunkId, hostId, existingReplica.getStatus());
                replicas.add(existingReplica);
                continue;
            }

            // Create new replica in PLANNED status — NOT ACTIVE.
            // The data plane (ReplicationTransferService) must transfer physical bytes
            // and verify checksums before promoting this to ACTIVE.
            ChunkReplica replica = ChunkReplica.builder()
                    .chunk(chunk)
                    .host(host)
                    .containerOffsetBytes(0L)
                    .status(ChunkReplica.Status.PLANNED)
                    .build();

            ChunkReplica saved = chunkReplicaRepository.save(replica);
            replicas.add(saved);
            existingReplicas.add(saved);
            log.debug("Created PLANNED replica {} for chunk {} on host {} — awaiting physical transfer",
                    saved.getId(), chunkId, hostId);
        }

        return replicas;
    }

    /**
     * Removes a replica record by its ID.
     *
     * @param replicaId the replica to remove
     * @throws ReplicationException if the replica is not found
     */
    @Transactional
    public void removeReplica(UUID replicaId) {
        ChunkReplica replica = chunkReplicaRepository.findById(replicaId)
                .orElseThrow(() -> new ReplicationException("Replica not found: " + replicaId));

        log.info("Removing replica {} for chunk {} from host {}",
                replicaId, replica.getChunk().getId(), replica.getHost().getId());
        chunkReplicaRepository.delete(replica);
    }

    /**
     * Updates the status of a replica.
     *
     * @param replicaId the replica to update
     * @param newStatus the new status
     * @throws ReplicationException if the replica is not found
     */
    @Transactional
    public void updateReplicaStatus(UUID replicaId, ChunkReplica.Status newStatus) {
        ChunkReplica replica = chunkReplicaRepository.findById(replicaId)
                .orElseThrow(() -> new ReplicationException("Replica not found: " + replicaId));

        ChunkReplica.Status oldStatus = replica.getStatus();
        replica.setStatus(newStatus);
        chunkReplicaRepository.save(replica);

        log.info("Updated replica {} status: {} -> {}", replicaId, oldStatus, newStatus);
    }

    /**
     * Verifies the replica count for a chunk and returns the deficit.
     *
     * @param chunkId the chunk to verify
     * @return the deficit (target - active count); 0 or negative means fully replicated
     */
    public int verifyReplicaCount(UUID chunkId) {
        long activeCount = chunkReplicaRepository.findByChunkId(chunkId).stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                .count();

        int deficit = config.getFactor() - (int) activeCount;

        if (deficit > 0) {
            log.warn("Chunk {} is under-replicated: active={}, target={}, deficit={}",
                    chunkId, activeCount, config.getFactor(), deficit);
        }

        return deficit;
    }

    /**
     * Finds all chunks that have fewer active replicas than the replication factor.
     *
     * @return list of under-replicated chunk IDs with their deficit
     */
    public Map<UUID, Integer> getUnderReplicatedChunks() {
        List<Object[]> rows = chunkReplicaRepository.findUnderReplicatedChunksAggregate(config.getFactor());
        Map<UUID, Integer> underReplicated = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UUID chunkId = (UUID) row[0];
            int deficit = ((Number) row[1]).intValue();
            if (deficit > 0) {
                underReplicated.put(chunkId, deficit);
            }
        }
        log.info("Found {} under-replicated chunks using aggregate query", underReplicated.size());
        return underReplicated;
    }

    /**
     * Returns all replicas for a given chunk.
     */
    public List<ChunkReplica> getReplicasByChunk(UUID chunkId) {
        return chunkReplicaRepository.findByChunkId(chunkId);
    }

    /**
     * Returns all replicas stored on a given host.
     */
    public List<ChunkReplica> getReplicasByHost(UUID hostId) {
        return chunkReplicaRepository.findByHostId(hostId);
    }

    /**
     * Generates a detailed {@link ReplicaInfoDto} for a given chunk.
     *
     * @param chunkId the chunk to generate metadata for
     * @return the replica info DTO
     * @throws ReplicationException if the chunk is not found
     */
    public ReplicaInfoDto generateReplicaMetadata(UUID chunkId) {
        Chunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new ReplicationException("Chunk not found: " + chunkId));

        List<ChunkReplica> replicas = chunkReplicaRepository.findByChunkId(chunkId);

        long activeCount = replicas.stream()
                .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                .count();

        List<ReplicaInfoDto.ReplicaPlacement> placements = replicas.stream()
                .map(r -> ReplicaInfoDto.ReplicaPlacement.builder()
                        .replicaId(r.getId())
                        .hostId(r.getHost().getId())
                        .hostName(r.getHost().getName())
                        .status(r.getStatus())
                        .build())
                .collect(Collectors.toList());

        return ReplicaInfoDto.builder()
                .chunkId(chunk.getId())
                .fileId(chunk.getFile().getId())
                .chunkIndex(chunk.getChunkIndex())
                .sizeBytes(chunk.getSizeBytes())
                .currentReplicaCount((int) activeCount)
                .targetReplicaCount(config.getFactor())
                .underReplicated(activeCount < config.getFactor())
                .placements(placements)
                .build();
    }

    /**
     * Generates replica metadata for all chunks in the system.
     */
    public List<ReplicaInfoDto> generateAllReplicaMetadata() {
        return chunkRepository.findAll().stream()
                .map(chunk -> generateReplicaMetadata(chunk.getId()))
                .collect(Collectors.toList());
    }
}
