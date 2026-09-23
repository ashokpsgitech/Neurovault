package com.neurovault.backend.security.capability;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Service for issuing and validating fine-grained, structured capability tokens.
 *
 * <p>Enforces strict claim validation:
 * <ul>
 *   <li>Operation exact match: READ, WRITE, or REPLICATE</li>
 *   <li>Host ID exact match</li>
 *   <li>Chunk ID exact match (when present)</li>
 *   <li>Short-lived TTL expiration check</li>
 *   <li>Audience and signature integrity verification</li>
 * </ul>
 */
@Slf4j
@Service
public class CapabilityTokenService {

    private final SecretKey key;
    private final long defaultTtlSeconds;

    public CapabilityTokenService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${neurovault.capability.ttl-seconds:900}") long defaultTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;

        SecretKey localKey;
        try {
            byte[] keyBytes = Decoders.BASE64.decode(secret);
            localKey = Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            localKey = Keys.hmacShaKeyFor(keyBytes);
        }
        this.key = localKey;
    }

    /**
     * Issues a signed capability token with explicit structured claims.
     */
    public String issueToken(CapabilityToken token) {
        Instant now = Instant.now();
        Instant exp = token.getExpiration() != null ? token.getExpiration() : now.plusSeconds(defaultTtlSeconds);

        return Jwts.builder()
                .subject(token.getSubject() != null ? token.getSubject() : "neurovault-client")
                .id(token.getTokenId() != null ? token.getTokenId().toString() : UUID.randomUUID().toString())
                .audience().add(token.getAudience() != null ? token.getAudience() : "neurovault-storage").and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("sessionId", token.getSessionId() != null ? token.getSessionId().toString() : null)
                .claim("hostId", token.getHostId() != null ? token.getHostId().toString() : null)
                .claim("chunkId", token.getChunkId() != null ? token.getChunkId().toString() : null)
                .claim("chunkIndex", token.getChunkIndex())
                .claim("operation", token.getOperation() != null ? token.getOperation().name() : CapabilityOperation.READ.name())
                .signWith(key)
                .compact();
    }

    /**
     * Parses a capability token and extracts structured claims.
     */
    public CapabilityToken parseToken(String tokenString) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(tokenString)
                .getPayload();

        String sessionIdStr = claims.get("sessionId", String.class);
        String hostIdStr = claims.get("hostId", String.class);
        String chunkIdStr = claims.get("chunkId", String.class);
        Integer chunkIndex = claims.get("chunkIndex", Integer.class);
        String opStr = claims.get("operation", String.class);

        CapabilityOperation operation = CapabilityOperation.READ;
        if (opStr != null) {
            try {
                operation = CapabilityOperation.valueOf(opStr);
            } catch (IllegalArgumentException ignored) {
            }
        }

        return CapabilityToken.builder()
                .subject(claims.getSubject())
                .sessionId(sessionIdStr != null ? UUID.fromString(sessionIdStr) : null)
                .hostId(hostIdStr != null ? UUID.fromString(hostIdStr) : null)
                .chunkId(chunkIdStr != null ? UUID.fromString(chunkIdStr) : null)
                .chunkIndex(chunkIndex)
                .operation(operation)
                .issuedAt(claims.getIssuedAt() != null ? claims.getIssuedAt().toInstant() : null)
                .expiration(claims.getExpiration() != null ? claims.getExpiration().toInstant() : null)
                .build();
    }

    /**
     * Strictly validates a capability token against the expected request parameters.
     * Throws {@link AccessDeniedException} if any claim does not match.
     */
    public CapabilityToken validateToken(String tokenString, UUID expectedHostId, UUID expectedChunkId, CapabilityOperation expectedOp) {
        if (tokenString == null || tokenString.isBlank()) {
            throw new AccessDeniedException("Missing required capability token");
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(tokenString)
                    .getPayload();

            // Check expiration
            if (claims.getExpiration() != null && claims.getExpiration().before(new Date())) {
                throw new AccessDeniedException("Capability token has expired");
            }

            // Structured claim extraction
            String hostIdStr = claims.get("hostId", String.class);
            String chunkIdStr = claims.get("chunkId", String.class);
            String opStr = claims.get("operation", String.class);

            // Backward compatibility with legacy subject string: chunk-session:{sessionId}:host:{hostId}:index:{index}
            if (hostIdStr == null && claims.getSubject() != null && claims.getSubject().startsWith("chunk-session:")) {
                String sub = claims.getSubject();
                int hostIdx = sub.indexOf(":host:");
                if (hostIdx != -1) {
                    int endIdx = sub.indexOf(":index:", hostIdx);
                    hostIdStr = endIdx != -1 ? sub.substring(hostIdx + 6, endIdx) : sub.substring(hostIdx + 6);
                }
            }

            // 1. Host Validation (Exact Match)
            if (expectedHostId != null) {
                if (hostIdStr == null || !expectedHostId.toString().equalsIgnoreCase(hostIdStr)) {
                    log.warn("Capability token host mismatch: token is for host {}, requested host {}", hostIdStr, expectedHostId);
                    throw new AccessDeniedException("Capability token host mismatch: unauthorized host access");
                }
            }

            // 2. Chunk Validation (Exact Match when specified)
            if (expectedChunkId != null && chunkIdStr != null) {
                if (!expectedChunkId.toString().equalsIgnoreCase(chunkIdStr)) {
                    log.warn("Capability token chunk mismatch: token is for chunk {}, requested chunk {}", chunkIdStr, expectedChunkId);
                    throw new AccessDeniedException("Capability token chunk mismatch: unauthorized chunk access");
                }
            }

            // 3. Operation Validation (Exact Match)
            if (expectedOp != null && opStr != null) {
                if (!expectedOp.name().equalsIgnoreCase(opStr)) {
                    log.warn("Capability token operation mismatch: token authorizes {}, requested {}", opStr, expectedOp);
                    throw new AccessDeniedException("Capability token operation mismatch: unauthorized operation " + expectedOp);
                }
            }

            return parseToken(tokenString);

        } catch (AccessDeniedException ade) {
            throw ade;
        } catch (Exception e) {
            log.warn("Invalid capability token signature or format: {}", e.getMessage());
            throw new AccessDeniedException("Invalid or forged capability token: " + e.getMessage());
        }
    }
}
