package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.Host;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Strategy interface for host selection algorithms.
 *
 * <p>Implementations are responsible for scoring and ranking eligible hosts
 * based on criteria such as capacity, load, existing replica count, and health.
 * The Strategy Pattern allows swapping algorithms (e.g., weighted-score, geographic-aware)
 * without modifying calling code.</p>
 *
 * <p>Usage example:</p>
 * <pre>
 *   List&lt;Host&gt; selected = strategy.selectHosts(3, chunkSizeBytes, excludeIds);
 * </pre>
 *
 * @author NeuroVault Team
 * @see WeightedScoreHostSelectionStrategy
 */
public interface HostSelectionStrategy {

    /**
     * Selects the best candidate hosts for storing a chunk.
     *
     * @param count          the number of hosts to select
     * @param chunkSizeBytes the size of the chunk to be stored (bytes)
     * @param excludeHostIds host IDs to exclude (e.g., hosts already holding a replica)
     * @return ordered list of selected hosts (best first), size may be less than {@code count}
     *         if insufficient eligible hosts are available
     */
    List<Host> selectHosts(int count, long chunkSizeBytes, Set<UUID> excludeHostIds);

    /**
     * Returns a human-readable name for this strategy (e.g., "WeightedScore").
     *
     * @return strategy name
     */
    String getStrategyName();
}
