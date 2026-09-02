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
 * <p>Handles the full lifecycle of replicas: assignment, removal, status updates,
 * verification, and metadata generation. This service operates at the <strong>metadata
 * level</strong> — it creates and manages {@link ChunkReplica} records but does not
 * perform actual byte-level data transfer (which is the Data Plane's responsibility).</p>
 *
 * @author NeuroVault Team
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
     * Assigns replicas for a chunk on the given hosts.
     *
     * <p>Creates a {@link ChunkReplica} record for each host with status {@code ACTIVE}.</p>
     *
     * @param chunkId the chunk to replicate
     * @param hostIds the hosts on which to place replicas
     * @return list of created replica records
     * @throws ReplicationException if the chunk or any host is not found
     */
    @Transactional
    public List<ChunkReplica> assignReplicas(UUID chunkId, List<UUID> hostIds) {
        log.info("Assigning {} replicas for chunk {}", hostIds.size(), chunkId);

        Chunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new ReplicationException("Chunk not found: " + chunkId));

        List<ChunkReplica> existingReplicas = chunkReplicaRepository.findByChunkId(chunkId);
        List<ChunkReplica> replicas = new ArrayList<>();
        for (UUID hostId : hostIds) {
            Host host = hostRepository.findById(hostId)
                    .orElseThrow(() -> new ReplicationException("Host not found: " + hostId));

            // Prevent duplicate replicas on the same host for the same chunk
            boolean alreadyExists = existingReplicas.stream()
                    .anyMatch(r -> r.getHost().getId().equals(hostId)
                            && r.getStatus() == ChunkReplica.Status.ACTIVE);
            if (alreadyExists) {
                log.warn("Replica already exists for chunk {} on host {} — skipping",
                        chunkId, hostId);
                continue;
            }

            ChunkReplica replica = ChunkReplica.builder()
                    .chunk(chunk)
                    .host(host)
                    .containerOffsetBytes(0L) // Will be set by the storage engine
                    .status(ChunkReplica.Status.ACTIVE)
                    .build();

            ChunkReplica saved = chunkReplicaRepository.save(replica);
            replicas.add(saved);
            existingReplicas.add(saved);
            log.debug("Created replica {} for chunk {} on host {}",
                    replica.getId(), chunkId, hostId);
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

        log.info("Updated replica {} status: {} → {}", replicaId, oldStatus, newStatus);
    }

    /**
     * Verifies the replica count for a chunk and returns the deficit.
     *
     * @param chunkId the chunk to verify
     * @return the deficit (target − active count); 0 or negative means fully replicated
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
        List<Chunk> activeChunks = chunkRepository.findByStatus(Chunk.Status.ACTIVE);
        Map<UUID, Integer> underReplicated = new LinkedHashMap<>();

        for (Chunk chunk : activeChunks) {
            long activeCount = chunkReplicaRepository.findByChunkId(chunk.getId()).stream()
                    .filter(r -> r.getStatus() == ChunkReplica.Status.ACTIVE)
                    .count();

            int deficit = config.getFactor() - (int) activeCount;
            if (deficit > 0) {
                underReplicated.put(chunk.getId(), deficit);
            }
        }

        log.info("Found {} under-replicated chunks", underReplicated.size());
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

    /**
     * Creates a new replica record with {@code SYNCING} status for self-healing transfers.
     * Unlike {@link #assignReplicas}, this does NOT set the status to ACTIVE — the caller
     * must verify the physical transfer before promoting to ACTIVE via {@link #markReplicaActive}.
     *
     * @param chunkId the chunk to replicate
     * @param hostId  the destination host
     * @return the created replica in SYNCING state
     * @throws ReplicationException if chunk or host not found, or replica already exists
     */
    @Transactional
    public ChunkReplica createSyncingReplica(UUID chunkId, UUID hostId) {
        log.info("Creating SYNCING replica for chunk {} on host {}", chunkId, hostId);

        Chunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new ReplicationException("Chunk not found: " + chunkId));

        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ReplicationException("Host not found: " + hostId));

        // Check for existing replica (any status) to prevent duplicates
        if (chunkReplicaRepository.findByChunkIdAndHostId(chunkId, hostId).isPresent()) {
            throw new ReplicationException(
                    String.format("Replica already exists for chunk %s on host %s", chunkId, hostId));
        }

        ChunkReplica replica = ChunkReplica.builder()
                .chunk(chunk)
                .host(host)
                .containerOffsetBytes(0L)
                .status(ChunkReplica.Status.SYNCING)
                .build();

        ChunkReplica saved = chunkReplicaRepository.save(replica);
        log.info("Created SYNCING replica {} for chunk {} on host {}", saved.getId(), chunkId, hostId);
        return saved;
    }

    /**
     * Promotes a SYNCING replica to ACTIVE after successful physical transfer and verification.
     *
     * @param replicaId the replica to promote
     * @throws ReplicationException if replica not found or not in SYNCING state
     */
    @Transactional
    public void markReplicaActive(UUID replicaId) {
        ChunkReplica replica = chunkReplicaRepository.findById(replicaId)
                .orElseThrow(() -> new ReplicationException("Replica not found: " + replicaId));

        if (replica.getStatus() != ChunkReplica.Status.SYNCING) {
            log.warn("Cannot promote replica {} — expected SYNCING but found {}",
                    replicaId, replica.getStatus());
            return;
        }

        replica.setStatus(ChunkReplica.Status.ACTIVE);
        chunkReplicaRepository.save(replica);
        log.info("Promoted replica {} to ACTIVE (chunk={}, host={})",
                replicaId, replica.getChunk().getId(), replica.getHost().getId());
    }

    /**
     * Finds a healthy source host that has an ACTIVE replica of the given chunk.
     *
     * @param chunkId the chunk to find a source for
     * @return an ONLINE host with an ACTIVE replica, or empty if none available
     */
    public Optional<Host> findHealthySourceHost(UUID chunkId) {
        List<ChunkReplica> activeReplicas = chunkReplicaRepository
                .findByChunkIdAndStatus(chunkId, ChunkReplica.Status.ACTIVE);

        return activeReplicas.stream()
                .map(ChunkReplica::getHost)
                .filter(host -> host.getStatus() == Host.Status.ONLINE)
                .findFirst();
    }
}
