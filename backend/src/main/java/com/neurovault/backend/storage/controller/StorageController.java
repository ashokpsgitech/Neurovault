package com.neurovault.backend.storage.controller;

import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.BadRequestException;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.host.dto.HostStatusDto;
import com.neurovault.backend.host.service.HostRegistrationService;
import com.neurovault.backend.repository.UserRepository;
import com.neurovault.backend.storage.dto.ChunkMetadataDto;
import com.neurovault.backend.storage.dto.CreateContainerRequest;
import com.neurovault.backend.storage.dto.StorageStatusResponse;
import com.neurovault.backend.storage.dto.StoreChunkRequest;
import com.neurovault.backend.storage.model.StorageReservationSize;
import com.neurovault.backend.storage.service.StorageService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing storage management endpoints.
 * Provides operations for container lifecycle and chunk CRUD.
 */
@RestController
@RequestMapping("/api/storage")
public class StorageController {

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    private final StorageService storageService;
    private final HostRegistrationService hostRegistrationService;
    private final UserRepository userRepository;
    private final com.neurovault.backend.security.JwtUtils jwtUtils;
    private final com.neurovault.backend.security.capability.CapabilityTokenService capabilityTokenService;

    public StorageController(
            StorageService storageService,
            HostRegistrationService hostRegistrationService,
            UserRepository userRepository,
            com.neurovault.backend.security.JwtUtils jwtUtils,
            com.neurovault.backend.security.capability.CapabilityTokenService capabilityTokenService) {
        this.storageService = storageService;
        this.hostRegistrationService = hostRegistrationService;
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
        this.capabilityTokenService = capabilityTokenService;
    }

    /**
     * Returns the storage status of a host's container.
     */
    @GetMapping("/status")
    public ResponseEntity<StorageStatusResponse> getStorageStatus(
            @RequestParam(required = false) UUID hostId,
            Principal principal) {
        UUID targetHostId = resolveHostId(hostId, principal);
        log.debug("GET /api/storage/status for host {}", targetHostId);
        StorageStatusResponse status = storageService.getStorageStatus(targetHostId);
        return ResponseEntity.ok(status);
    }

    /**
     * Creates a new storage container for a host.
     */
    @PostMapping("/create")
    public ResponseEntity<StorageStatusResponse> createStorage(
            @RequestBody CreateContainerRequest request,
            Principal principal) {
        UUID targetHostId = resolveHostId(request != null ? request.getHostId() : null, principal);
        StorageReservationSize size = (request != null && request.getReservationSize() != null)
                ? request.getReservationSize()
                : StorageReservationSize.GB_5;

        log.info("POST /api/storage/create for host {} with size {}", targetHostId, size);
        String containerPath = (request != null) ? request.getContainerPath() : null;
        StorageStatusResponse status = storageService.createStorage(targetHostId, size, containerPath);
        return ResponseEntity.status(HttpStatus.CREATED).body(status);
    }

    /**
     * Deletes a host's storage container.
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Void> deleteStorage(
            @RequestParam(required = false) UUID hostId,
            Principal principal) {
        UUID targetHostId = resolveHostId(hostId, principal);
        log.info("DELETE /api/storage/delete for host {}", targetHostId);
        storageService.deleteStorage(targetHostId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lists all stored chunk metadata in a host's container.
     */
    @GetMapping("/chunks")
    public ResponseEntity<List<ChunkMetadataDto>> listChunks(
            @RequestParam(required = false) UUID hostId,
            Principal principal) {
        UUID targetHostId = resolveHostId(hostId, principal);
        log.debug("GET /api/storage/chunks for host {}", targetHostId);
        List<ChunkMetadataDto> chunks = storageService.listChunks(targetHostId);
        return ResponseEntity.ok(chunks);
    }

    /**
     * Stores an encrypted chunk in a host's container.
     */
    @PostMapping("/chunks")
    public ResponseEntity<ChunkMetadataDto> storeChunk(
            @RequestParam(required = false) UUID hostId,
            @RequestHeader(value = "X-Chunk-Token", required = false) String chunkToken,
            @Valid @RequestBody StoreChunkRequest request,
            Principal principal) {
        UUID targetHostId;
        if (chunkToken != null && !chunkToken.isBlank()) {
            targetHostId = hostId != null ? hostId : resolveHostId(null, principal);
            capabilityTokenService.validateToken(
                    chunkToken, targetHostId, request.getChunkId(),
                    com.neurovault.backend.security.capability.CapabilityOperation.WRITE);
        } else {
            targetHostId = resolveHostId(hostId, principal);
        }

        if (request.getOwnerId() == null && principal != null) {
            UUID ownerId = extractUserId(principal);
            if (ownerId != null) {
                request.setOwnerId(ownerId);
            }
        }
        log.info("POST /api/storage/chunks for host {} chunk {}", targetHostId, request.getChunkId());
        ChunkMetadataDto metadata = storageService.storeChunk(targetHostId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(metadata);
    }

    /**
     * Reads an encrypted chunk from a host's container.
     */
    @GetMapping("/chunks/{chunkId}")
    public ResponseEntity<byte[]> readChunk(
            @PathVariable UUID chunkId,
            @RequestParam(required = false) UUID hostId,
            @RequestHeader(value = "X-Chunk-Token", required = false) String chunkToken,
            Principal principal) {
        UUID targetHostId;
        if (chunkToken != null && !chunkToken.isBlank()) {
            targetHostId = hostId != null ? hostId : resolveHostId(null, principal);
            capabilityTokenService.validateToken(
                    chunkToken, targetHostId, chunkId,
                    com.neurovault.backend.security.capability.CapabilityOperation.READ);
        } else {
            targetHostId = resolveHostId(hostId, principal);
        }

        log.debug("GET /api/storage/chunks/{} for host {}", chunkId, targetHostId);
        byte[] data = storageService.readChunk(targetHostId, chunkId);
        return ResponseEntity.ok(data);
    }

    /**
     * Deletes a chunk from a host's container.
     */
    @DeleteMapping("/chunks/{chunkId}")
    public ResponseEntity<Void> deleteChunk(
            @PathVariable UUID chunkId,
            @RequestParam(required = false) UUID hostId,
            Principal principal) {
        UUID targetHostId = resolveHostId(hostId, principal);
        log.info("DELETE /api/storage/chunks/{} for host {}", chunkId, targetHostId);
        storageService.deleteChunk(targetHostId, chunkId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Extracts user UUID from principal name (supporting both UUID strings and user email).
     */
    private UUID extractUserId(Principal principal) {
        if (principal == null) return null;
        String name = principal.getName();
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            User user = userRepository.findByEmail(name).orElse(null);
            return user != null ? user.getId() : null;
        }
    }

    /**
     * Resolves host ID from request parameter or authenticated user's registered host.
     * Enforces strict host ownership check to eliminate IDOR vulnerabilities.
     */
    private UUID resolveHostId(UUID explicitHostId, Principal principal) {
        UUID ownerId = extractUserId(principal);
        if (explicitHostId != null) {
            if (ownerId != null) {
                HostStatusDto host = hostRegistrationService.getHostById(explicitHostId);
                if (!host.getOwnerId().equals(ownerId)) {
                    log.warn("IDOR access violation: User {} attempted to access host {} owned by {}",
                            ownerId, explicitHostId, host.getOwnerId());
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Access denied: You do not have permission to manage host " + explicitHostId);
                }
            }
            return explicitHostId;
        }
        if (ownerId != null) {
            List<HostStatusDto> hosts = hostRegistrationService.getHostsByOwner(ownerId);
            if (!hosts.isEmpty()) {
                return hosts.get(0).getHostId();
            }
        }
        throw new BadRequestException("No hostId provided and no registered host found for user. Please include hostId parameter.");
    }
}
