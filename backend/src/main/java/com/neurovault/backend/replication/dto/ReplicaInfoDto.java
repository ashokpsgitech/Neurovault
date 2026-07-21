package com.neurovault.backend.replication.dto;

import com.neurovault.backend.entity.ChunkReplica;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * DTO representing the replication status of a single chunk.
 *
 * <p>Returned as part of {@code GET /api/cluster/replicas}.</p>
 *
 * @author NeuroVault Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplicaInfoDto {

    /** Unique chunk identifier. */
    private UUID chunkId;

    /** Identifier of the file this chunk belongs to. */
    private UUID fileId;

    /** Index position of this chunk within the file. */
    private int chunkIndex;

    /** Chunk size in bytes. */
    private long sizeBytes;

    /** Current number of active replicas for this chunk. */
    private int currentReplicaCount;

    /** Target replication factor for this chunk. */
    private int targetReplicaCount;

    /** Whether the chunk is under-replicated. */
    private boolean underReplicated;

    /** Placement details — which hosts hold replicas and their status. */
    @Builder.Default
    private List<ReplicaPlacement> placements = List.of();

    /**
     * Represents a single replica placement on a host.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReplicaPlacement {
        /** Replica identifier. */
        private UUID replicaId;
        /** Host identifier holding this replica. */
        private UUID hostId;
        /** Human-readable host name. */
        private String hostName;
        /** Status of this replica. */
        private ChunkReplica.Status status;
    }
}
