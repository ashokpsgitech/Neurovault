package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.ChunkReplica;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.replication.exception.InsufficientHostsException;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.ChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Facade service for host selection, delegating to the active {@link HostSelectionStrategy}.
 *
 * <p>Provides higher-level methods for common use cases like initial replica placement
 * and replacement host selection during self-healing.</p>
 *
 * @author NeuroVault Team
 */
@Slf4j
@Service
public class HostSelectionService {

    private final HostSelectionStrategy selectionStrategy;
    private final ChunkReplicaRepository chunkReplicaRepository;
    private final ChunkRepository chunkRepository;
    private final ReplicationConfig config;

    public HostSelectionService(HostSelectionStrategy selectionStrategy,
                                ChunkReplicaRepository chunkReplicaRepository,
                                ChunkRepository chunkRepository,
                                ReplicationConfig config) {
        this.selectionStrategy = selectionStrategy;
        this.chunkReplicaRepository = chunkReplicaRepository;
        this.chunkRepository = chunkRepository;
        this.config = config;
    }

    /**
     * Selects hosts for initial placement of a chunk's replicas.
     *
     * @param chunkId      the chunk to place
     * @param replicaCount number of replicas desired
     * @return list of selected hosts
     * @throws InsufficientHostsException if not enough eligible hosts are available
     */
    public List<Host> selectHostsForChunk(UUID chunkId, int replicaCount) {
        log.info("Selecting {} hosts for chunk {} using strategy '{}'",
                replicaCount, chunkId, selectionStrategy.getStrategyName());

        long chunkSizeBytes = chunkRepository.findById(chunkId)
                .map(chunk -> chunk.getSizeBytes())
                .orElseThrow(() -> new InsufficientHostsException(
                        "Chunk not found: " + chunkId));

        // Exclude hosts that already hold a replica for this chunk
        Set<UUID> existingHostIds = chunkReplicaRepository.findByChunkId(chunkId).stream()
                .map(replica -> replica.getHost().getId())
                .collect(Collectors.toSet());

        List<Host> selected = selectionStrategy.selectHosts(
                replicaCount, chunkSizeBytes, existingHostIds);

        if (selected.size() < replicaCount) {
            log.warn("Only {} hosts available out of {} required for chunk {}",
                    selected.size(), replicaCount, chunkId);
            throw new InsufficientHostsException(replicaCount, selected.size());
        }

        return selected;
    }

    /**
     * Selects a single replacement host for a chunk during self-healing.
     *
     * <p>Excludes all hosts that currently hold an active replica for the chunk.</p>
     *
     * @param chunkId        the chunk needing a replacement host
     * @param currentHostIds IDs of hosts currently holding replicas
     * @return the selected replacement host
     * @throws InsufficientHostsException if no eligible replacement host is available
     */
    public Host selectReplacementHost(UUID chunkId, Set<UUID> currentHostIds) {
        log.info("Selecting replacement host for chunk {}, excluding {} hosts",
                chunkId, currentHostIds.size());

        long chunkSizeBytes = chunkRepository.findById(chunkId)
                .map(chunk -> chunk.getSizeBytes())
                .orElseThrow(() -> new InsufficientHostsException(
                        "Chunk not found: " + chunkId));

        List<Host> candidates = selectionStrategy.selectHosts(
                1, chunkSizeBytes, currentHostIds);

        if (candidates.isEmpty()) {
            throw new InsufficientHostsException(1, 0);
        }

        Host selected = candidates.get(0);
        log.info("Selected replacement host {} (name={}) for chunk {}",
                selected.getId(), selected.getName(), chunkId);

        return selected;
    }

    /**
     * Returns the currently active selection strategy name.
     */
    public String getActiveStrategyName() {
        return selectionStrategy.getStrategyName();
    }
}
