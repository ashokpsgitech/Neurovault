package com.neurovault.backend.storage.controller;

import com.neurovault.backend.storage.dto.ChunkMetadataDto;
import com.neurovault.backend.storage.dto.CreateContainerRequest;
import com.neurovault.backend.storage.dto.StorageStatusResponse;
import com.neurovault.backend.storage.dto.StoreChunkRequest;
import com.neurovault.backend.storage.service.StorageService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * Returns the storage status of a host's container.
     *
     * @param hostId the UUID of the host
     * @return storage status including container size, used/free space, and chunk count
     */
    @GetMapping("/status")
    public ResponseEntity<StorageStatusResponse> getStorageStatus(@RequestParam UUID hostId) {
        log.debug("GET /api/storage/status for host {}", hostId);
        StorageStatusResponse status = storageService.getStorageStatus(hostId);
        return ResponseEntity.ok(status);
    }

    /**
     * Creates a new storage container for a host.
     *
     * @param request the creation request with host ID and reservation size
     * @return storage status after creation
     */
    @PostMapping("/create")
    public ResponseEntity<StorageStatusResponse> createStorage(@Valid @RequestBody CreateContainerRequest request) {
        log.info("POST /api/storage/create for host {} with size {}",
                request.getHostId(), request.getReservationSize());
        StorageStatusResponse status = storageService.createStorage(
                request.getHostId(), request.getReservationSize());
        return ResponseEntity.status(HttpStatus.CREATED).body(status);
    }

    /**
     * Deletes a host's storage container.
     *
     * @param hostId the UUID of the host
     * @return 204 No Content on success
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Void> deleteStorage(@RequestParam UUID hostId) {
        log.info("DELETE /api/storage/delete for host {}", hostId);
        storageService.deleteStorage(hostId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lists all stored chunk metadata in a host's container.
     *
     * @param hostId the UUID of the host
     * @return list of chunk metadata
     */
    @GetMapping("/chunks")
    public ResponseEntity<List<ChunkMetadataDto>> listChunks(@RequestParam UUID hostId) {
        log.debug("GET /api/storage/chunks for host {}", hostId);
        List<ChunkMetadataDto> chunks = storageService.listChunks(hostId);
        return ResponseEntity.ok(chunks);
    }

    /**
     * Stores an encrypted chunk in a host's container.
     *
     * @param hostId  the UUID of the host
     * @param request the chunk data and metadata
     * @return the stored chunk's metadata
     */
    @PostMapping("/chunks")
    public ResponseEntity<ChunkMetadataDto> storeChunk(
            @RequestParam UUID hostId,
            @Valid @RequestBody StoreChunkRequest request) {
        log.info("POST /api/storage/chunks for host {} chunk {}", hostId, request.getChunkId());
        ChunkMetadataDto metadata = storageService.storeChunk(hostId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(metadata);
    }

    /**
     * Reads an encrypted chunk from a host's container.
     *
     * @param chunkId the UUID of the chunk
     * @param hostId  the UUID of the host
     * @return the raw encrypted bytes
     */
    @GetMapping("/chunks/{chunkId}")
    public ResponseEntity<byte[]> readChunk(
            @PathVariable UUID chunkId,
            @RequestParam UUID hostId) {
        log.debug("GET /api/storage/chunks/{} for host {}", chunkId, hostId);
        byte[] data = storageService.readChunk(hostId, chunkId);
        return ResponseEntity.ok(data);
    }

    /**
     * Deletes a chunk from a host's container.
     *
     * @param chunkId the UUID of the chunk
     * @param hostId  the UUID of the host
     * @return 204 No Content on success
     */
    @DeleteMapping("/chunks/{chunkId}")
    public ResponseEntity<Void> deleteChunk(
            @PathVariable UUID chunkId,
            @RequestParam UUID hostId) {
        log.info("DELETE /api/storage/chunks/{} for host {}", chunkId, hostId);
        storageService.deleteChunk(hostId, chunkId);
        return ResponseEntity.noContent().build();
    }
}
