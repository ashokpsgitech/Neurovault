package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.Chunk;
import com.neurovault.backend.entity.ChunkReplica;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.replication.exception.InsufficientCapacityException;
import com.neurovault.backend.replication.exception.ReplicationException;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.ChunkRepository;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.storage.exception.CorruptedChunkException;
import com.neurovault.backend.transport.ChunkTransport;
import com.neurovault.backend.transport.LocalContainerChunkTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated Data-Plane Physical Replication Service.
 *
 * <p>Enforces physical byte-level replication lifecycle:
 * <pre>
 *   PLANNED → RESERVED → TRANSFERRING → VERIFIED → ACTIVE
 * </pre>
 * Replicas are NEVER marked ACTIVE until physical encrypted bytes are durably
 * committed and their streaming SHA-256 hash strictly matches the authoritative chunk checksum.
 */
@Service
public class ReplicationTransferService {

    private static final Logger log = LoggerFactory.getLogger(ReplicationTransferService.class);

    private final ChunkRepository chunkRepository;
    private final ChunkReplicaRepository replicaRepository;
    private final HostRepository hostRepository;
    private final LocalContainerChunkTransport localTransport;

    public ReplicationTransferService(
            ChunkRepository chunkRepository,
            ChunkReplicaRepository replicaRepository,
            HostRepository hostRepository,
            LocalContainerChunkTransport localTransport) {
        this.chunkRepository = chunkRepository;
        this.replicaRepository = replicaRepository;
        this.hostRepository = hostRepository;
        this.localTransport = localTransport;
    }

    /**
     * Physically copies an encrypted chunk from source host to target host with bounded streaming.
     * Idempotent: returns existing replica if target already has an ACTIVE verified copy.
     */
    @Transactional
    public ChunkReplica copyChunk(UUID sourceHostId, UUID targetHostId, UUID chunkId, String capabilityToken) {
        log.info("Starting physical chunk copy: chunk {} from source host {} to target host {}",
                chunkId, sourceHostId, targetHostId);

        Chunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new ResourceNotFoundException("Chunk not found: " + chunkId));

        Host targetHost = hostRepository.findById(targetHostId)
                .orElseThrow(() -> new ResourceNotFoundException("Target host not found: " + targetHostId));

        long chunkSize = chunk.getSizeBytes();
        String expectedChecksum = chunk.getChecksum();

        // 1. Idempotency Check: if target already has an ACTIVE verified replica, return it
        List<ChunkReplica> existingReplicas = replicaRepository.findByChunkId(chunkId);
        Optional<ChunkReplica> existingTarget = existingReplicas.stream()
                .filter(r -> r.getHost().getId().equals(targetHostId))
                .findFirst();

        if (existingTarget.isPresent()) {
            ChunkReplica targetReplica = existingTarget.get();
            if (targetReplica.getStatus() == ChunkReplica.Status.ACTIVE) {
                // Verify physical existence
                if (localTransport.verifyChunkIntegrity(targetHostId, chunkId, expectedChecksum)) {
                    log.info("Idempotent check: target host {} already has ACTIVE verified replica for chunk {}",
                            targetHostId, chunkId);
                    return targetReplica;
                } else {
                    log.warn("Target replica for chunk {} was marked ACTIVE but physical bytes are missing/corrupt. Re-replicating.", chunkId);
                    targetReplica.setStatus(ChunkReplica.Status.CORRUPTED);
                    replicaRepository.save(targetReplica);
                }
            }
        }

        // 2. Capacity Reservation Check
        long availableCapacity = targetHost.getTotalCapacityBytes()
                - targetHost.getReservedCapacityBytes()
                - targetHost.getUsedCapacityBytes();

        if (availableCapacity < chunkSize) {
            log.error("Insufficient capacity on target host {}: available {} bytes, required {} bytes",
                    targetHostId, availableCapacity, chunkSize);
            throw new InsufficientCapacityException(
                    "Target host " + targetHostId + " has insufficient capacity (" + availableCapacity + " < " + chunkSize + ")");
        }

        // Reserve capacity
        targetHost.setReservedCapacityBytes(targetHost.getReservedCapacityBytes() + chunkSize);
        hostRepository.save(targetHost);

        // 3. Lifecycle: PLANNED -> RESERVED
        ChunkReplica replica = existingTarget.orElseGet(() -> ChunkReplica.builder()
                .chunk(chunk)
                .host(targetHost)
                .containerOffsetBytes(0L)
                .status(ChunkReplica.Status.PLANNED)
                .build());

        replica.setStatus(ChunkReplica.Status.RESERVED);
        replica = replicaRepository.save(replica);

        try {
            // 4. Lifecycle: RESERVED -> TRANSFERRING
            replica.setStatus(ChunkReplica.Status.TRANSFERRING);
            replica = replicaRepository.save(replica);

            UUID ownerId = chunk.getFile() != null && chunk.getFile().getOwner() != null
                    ? chunk.getFile().getOwner().getId()
                    : UUID.randomUUID();

            // 5. Open source stream and stream to target host container using bounded 64KB buffers
            try (InputStream sourceStream = localTransport.openReadStream(sourceHostId, chunkId, capabilityToken)) {
                localTransport.streamToTarget(targetHostId, chunkId, ownerId, sourceStream,
                        chunkSize, expectedChecksum, capabilityToken);
            }

            // 6. Lifecycle: TRANSFERRING -> VERIFIED
            boolean verified = localTransport.verifyChunkIntegrity(targetHostId, chunkId, expectedChecksum);
            if (!verified) {
                log.error("Physical verification failed after transfer for chunk {} on host {}", chunkId, targetHostId);
                replica.setStatus(ChunkReplica.Status.CORRUPTED);
                replicaRepository.save(replica);
                throw new CorruptedChunkException("Checksum mismatch after physical replication for chunk " + chunkId);
            }

            replica.setStatus(ChunkReplica.Status.VERIFIED);
            replica = replicaRepository.save(replica);

            // 7. Lifecycle: VERIFIED -> ACTIVE (Commit capacity usage)
            targetHost.setReservedCapacityBytes(Math.max(0, targetHost.getReservedCapacityBytes() - chunkSize));
            targetHost.setUsedCapacityBytes(targetHost.getUsedCapacityBytes() + chunkSize);
            hostRepository.save(targetHost);

            replica.setStatus(ChunkReplica.Status.ACTIVE);
            replica = replicaRepository.save(replica);

            log.info("Physical chunk replication SUCCEEDED: chunk {} is now ACTIVE on host {}",
                    chunkId, targetHostId);

            return replica;

        } catch (Exception e) {
            log.error("Physical replication failed for chunk {} → host {}: {}",
                    chunkId, targetHostId, e.getMessage(), e);

            // Release capacity reservation on failure
            targetHost.setReservedCapacityBytes(Math.max(0, targetHost.getReservedCapacityBytes() - chunkSize));
            hostRepository.save(targetHost);

            replica.setStatus(ChunkReplica.Status.FAILED);
            replicaRepository.save(replica);

            if (e instanceof ReplicationException) {
                throw (ReplicationException) e;
            }
            throw new ReplicationException("Failed to copy chunk " + chunkId + " to host " + targetHostId + ": " + e.getMessage(), e);
        }
    }
}
