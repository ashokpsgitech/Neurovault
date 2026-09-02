package com.neurovault.backend.storage.controller;

import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.BadRequestException;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.host.dto.HostStatusDto;
import com.neurovault.backend.host.service.HostRegistrationService;
import com.neurovault.backend.repository.UserRepository;
import com.neurovault.backend.storage.dto.ChunkMetadataDto;
import com.neurovault.backend.storage.dto.ChunkTransferRequest;
import com.neurovault.backend.storage.dto.ChunkTransferResponse;
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
     * Transfers a chunk from a source host's container to a destination host's container.
     * This endpoint is called by the Coordinator (SelfHealingService) to initiate a
     * host-to-host repair transfer. In the current single-process architecture, both
     * containers are managed by the same JVM.
     *
     * <p>Flow:
     * <ol>
     *   <li>Read chunk data from source host's container</li>
     *   <li>Compute SHA-256 hash and verify against expected checksum</li>
     *   <li>Store chunk data in destination host's container</li>
     *   <li>Verify the stored chunk's hash matches</li>
     *   <li>Return transfer result</li>
     * </ol>
     */
    @PostMapping("/transfer")
    public ResponseEntity<ChunkTransferResponse> transferChunk(
            @RequestBody ChunkTransferRequest request) {

        log.info("POST /api/storage/transfer — chunk {} from host {} to host {}",
                request.getChunkId(), request.getSourceHostId(), request.getDestinationHostId());

        try {
            // 1. Read chunk from source host
            byte[] chunkData = storageService.readChunk(
                    request.getSourceHostId(), request.getChunkId());

            if (chunkData == null || chunkData.length == 0) {
                return ResponseEntity.ok(ChunkTransferResponse.builder()
                        .success(false)
                        .chunkId(request.getChunkId())
                        .sourceHostId(request.getSourceHostId())
                        .destinationHostId(request.getDestinationHostId())
                        .errorMessage("Chunk data not found on source host")
                        .build());
            }

            // 2. Verify source integrity against expected checksum
            if (request.getExpectedChecksum() != null && !request.getExpectedChecksum().isEmpty()) {
                String sourceHash = computeSha256(chunkData);
                if (!sourceHash.equalsIgnoreCase(request.getExpectedChecksum())) {
                    log.error("Source integrity check failed for chunk {}: expected={}, actual={}",
                            request.getChunkId(), request.getExpectedChecksum(), sourceHash);
                    return ResponseEntity.ok(ChunkTransferResponse.builder()
                            .success(false)
                            .chunkId(request.getChunkId())
                            .sourceHostId(request.getSourceHostId())
                            .destinationHostId(request.getDestinationHostId())
                            .errorMessage("Source integrity verification failed")
                            .build());
                }
            }

            // 3. Store chunk on destination host
            StoreChunkRequest storeRequest = StoreChunkRequest.builder()
                    .chunkId(request.getChunkId())
                    .ownerId(request.getOwnerId())
                    .data(chunkData)
                    .build();

            ChunkMetadataDto destMetadata = storageService.storeChunk(
                    request.getDestinationHostId(), storeRequest);

            // 4. Verify destination integrity
            String destinationChecksum = destMetadata.getSha256Hash();

            boolean checksumMatch = request.getExpectedChecksum() == null
                    || request.getExpectedChecksum().isEmpty()
                    || destinationChecksum.equalsIgnoreCase(request.getExpectedChecksum());

            if (!checksumMatch) {
                log.error("Destination integrity check failed for chunk {}: expected={}, destination={}",
                        request.getChunkId(), request.getExpectedChecksum(), destinationChecksum);
                return ResponseEntity.ok(ChunkTransferResponse.builder()
                        .success(false)
                        .chunkId(request.getChunkId())
                        .sourceHostId(request.getSourceHostId())
                        .destinationHostId(request.getDestinationHostId())
                        .destinationChecksum(destinationChecksum)
                        .errorMessage("Destination integrity verification failed")
                        .build());
            }

            log.info("Chunk {} successfully transferred from host {} to host {}",
                    request.getChunkId(), request.getSourceHostId(), request.getDestinationHostId());

            return ResponseEntity.ok(ChunkTransferResponse.builder()
                    .success(true)
                    .chunkId(request.getChunkId())
                    .sourceHostId(request.getSourceHostId())
                    .destinationHostId(request.getDestinationHostId())
                    .destinationChecksum(destinationChecksum)
                    .build());

        } catch (Exception e) {
            log.error("Transfer failed for chunk {}: {}", request.getChunkId(), e.getMessage(), e);
            return ResponseEntity.ok(ChunkTransferResponse.builder()
                    .success(false)
                    .chunkId(request.getChunkId())
                    .sourceHostId(request.getSourceHostId())
                    .destinationHostId(request.getDestinationHostId())
                    .errorMessage("Transfer failed: " + e.getMessage())
                    .build());
        }
    }

    /**
     * Computes SHA-256 hash of the given data.
     */
    private String computeSha256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
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
        throw new BadRequestException("No hostId provided and no registered host found for user. Please include hostId parameter.");
    }
}
