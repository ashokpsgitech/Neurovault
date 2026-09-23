package com.neurovault.backend.security.capability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Structured model representing capability token claims.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapabilityToken {

    /** Subject: user identifier or authenticated principal */
    private String subject;

    /** Associated upload/download session */
    private UUID sessionId;

    /** Authorized target host node */
    private UUID hostId;

    /** Authorized chunk ID (optional if pre-allocation/session scoped) */
    private UUID chunkId;

    /** Chunk index */
    private Integer chunkIndex;

    /** Authorized operation: READ, WRITE, or REPLICATE */
    private CapabilityOperation operation;

    /** Audience, default "neurovault-storage" */
    @Builder.Default
    private String audience = "neurovault-storage";

    /** Issue timestamp */
    private Instant issuedAt;

    /** Expiration timestamp (short-lived TTL, e.g. 5-15 minutes) */
    private Instant expiration;

    /** Unique token identifier for replay prevention */
    @Builder.Default
    private UUID tokenId = UUID.randomUUID();
}
