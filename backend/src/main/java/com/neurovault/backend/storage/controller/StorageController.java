package com.neurovault.backend.storage.controller;

import com.neurovault.backend.entity.User;
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

    public StorageController(
            StorageService storageService,
            HostRegistrationService hostRegistrationService,
            UserRepository userRepository) {
        this.storageService = storageService;
        this.hostRegistrationService = hostRegistrationService;
        this.userRepository = userRepository;
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
            @Valid @RequestBody StoreChunkRequest request,
            Principal principal) {
        UUID targetHostId = resolveHostId(hostId, principal);
        if (request.getOwnerId() == null && principal != null) {
            try {
                String name = principal.getName();
                try {
                    request.setOwnerId(UUID.fromString(name));
                } catch (IllegalArgumentException e) {
                    User user = userRepository.findByEmail(name).orElse(null);
                    if (user != null) request.setOwnerId(user.getId());
                }
            } catch (Exception ignored) {}
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
            Principal principal) {
        UUID targetHostId = resolveHostId(hostId, principal);
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
     * Resolves host ID from request parameter or authenticated user's registered host.
     */
    private UUID resolveHostId(UUID explicitHostId, Principal principal) {
        if (explicitHostId != null) {
            return explicitHostId;
        }
        if (principal != null) {
            String name = principal.getName();
            UUID ownerId;
            try {
                ownerId = UUID.fromString(name);
            } catch (IllegalArgumentException e) {
                User user = userRepository.findByEmail(name)
                        .orElseThrow(() -> new ResourceNotFoundException("User not found: " + name));
                ownerId = user.getId();
            }
            List<HostStatusDto> hosts = hostRegistrationService.getHostsByOwner(ownerId);
            if (!hosts.isEmpty()) {
                return hosts.get(0).getHostId();
            }
        }
        throw new ResourceNotFoundException("No host ID provided and no registered host found for user");
    }
}
