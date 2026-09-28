package com.neurovault.backend.security.capability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityTokenServiceTest {

    private CapabilityTokenService tokenService;
    private final String secret = "dGVzdC1zZWNyZXQta2V5LWZvci1uZXVyb3ZhdWx0LWRpc3RyaWJ1dGVkLXN5c3RlbS1oYXJkZW5pbmc=";

    @BeforeEach
    void setUp() {
        tokenService = new CapabilityTokenService(secret, 900);
    }

    @Test
    @DisplayName("Should successfully issue and validate token with exact matching claims")
    void testIssueAndValidate_Success() {
        UUID hostId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();

        CapabilityToken token = CapabilityToken.builder()
                .hostId(hostId)
                .chunkId(chunkId)
                .operation(CapabilityOperation.READ)
                .build();

        String tokenStr = tokenService.issueToken(token);
        assertNotNull(tokenStr);

        CapabilityToken parsed = tokenService.validateToken(tokenStr, hostId, chunkId, CapabilityOperation.READ);
        assertNotNull(parsed);
        assertEquals(hostId, parsed.getHostId());
        assertEquals(chunkId, parsed.getChunkId());
        assertEquals(CapabilityOperation.READ, parsed.getOperation());
    }

    @Test
    @DisplayName("NV-P0-06: Replaying a WRITE token must throw AccessDeniedException")
    void testReplayPrevention_WriteToken() {
        UUID hostId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();

        CapabilityToken token = CapabilityToken.builder()
                .hostId(hostId)
                .chunkId(chunkId)
                .operation(CapabilityOperation.WRITE)
                .build();

        String tokenStr = tokenService.issueToken(token);

        // First use: succeeds
        CapabilityToken firstUse = tokenService.validateToken(tokenStr, hostId, chunkId, CapabilityOperation.WRITE);
        assertNotNull(firstUse);

        // Second use (replay attempt): must fail!
        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                tokenService.validateToken(tokenStr, hostId, chunkId, CapabilityOperation.WRITE)
        );
        assertTrue(ex.getMessage().contains("replay detected"));
    }

    @Test
    @DisplayName("READ tokens are reusable until expiry")
    void testReadToken_Reusable() {
        UUID hostId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();

        CapabilityToken token = CapabilityToken.builder()
                .hostId(hostId)
                .chunkId(chunkId)
                .operation(CapabilityOperation.READ)
                .build();

        String tokenStr = tokenService.issueToken(token);

        // Multiple reads are allowed
        assertNotNull(tokenService.validateToken(tokenStr, hostId, chunkId, CapabilityOperation.READ));
        assertNotNull(tokenService.validateToken(tokenStr, hostId, chunkId, CapabilityOperation.READ));
    }

    @Test
    @DisplayName("NV-P1-01: Token with omitted chunkId claim must be rejected when expectedChunkId is required")
    void testMissingChunkId_Rejected() {
        UUID hostId = UUID.randomUUID();
        UUID expectedChunkId = UUID.randomUUID();

        // Token without chunkId
        CapabilityToken token = CapabilityToken.builder()
                .hostId(hostId)
                .chunkId(null)
                .operation(CapabilityOperation.READ)
                .build();

        String tokenStr = tokenService.issueToken(token);

        // Validating with expectedChunkId must fail even though token.chunkId was null
        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                tokenService.validateToken(tokenStr, hostId, expectedChunkId, CapabilityOperation.READ)
        );
        assertTrue(ex.getMessage().contains("chunk mismatch"));
    }

    @Test
    @DisplayName("Host mismatch must throw AccessDeniedException")
    void testHostMismatch_Rejected() {
        UUID hostId = UUID.randomUUID();
        UUID wrongHostId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();

        CapabilityToken token = CapabilityToken.builder()
                .hostId(hostId)
                .chunkId(chunkId)
                .operation(CapabilityOperation.READ)
                .build();

        String tokenStr = tokenService.issueToken(token);

        assertThrows(AccessDeniedException.class, () ->
                tokenService.validateToken(tokenStr, wrongHostId, chunkId, CapabilityOperation.READ)
        );
    }

    @Test
    @DisplayName("Operation mismatch must throw AccessDeniedException")
    void testOperationMismatch_Rejected() {
        UUID hostId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();

        CapabilityToken token = CapabilityToken.builder()
                .hostId(hostId)
                .chunkId(chunkId)
                .operation(CapabilityOperation.READ)
                .build();

        String tokenStr = tokenService.issueToken(token);

        assertThrows(AccessDeniedException.class, () ->
                tokenService.validateToken(tokenStr, hostId, chunkId, CapabilityOperation.WRITE)
        );
    }
}
