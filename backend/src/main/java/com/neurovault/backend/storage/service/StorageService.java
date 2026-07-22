package com.neurovault.backend.storage.service;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.StorageContainer;
import com.neurovault.backend.exception.BadRequestException;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.StorageContainerRepository;
import com.neurovault.backend.storage.config.StorageProperties;
import com.neurovault.backend.storage.container.ContainerManager;
import com.neurovault.backend.storage.dto.ChunkMetadataDto;
import com.neurovault.backend.storage.dto.StorageStatusResponse;
import com.neurovault.backend.storage.dto.StoreChunkRequest;
import com.neurovault.backend.storage.engine.StorageEngine;
import com.neurovault.backend.storage.exception.ContainerException;
import com.neurovault.backend.storage.model.ChunkMetadata;
import com.neurovault.backend.storage.model.StorageReservationSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service bridging REST controllers with the StorageEngine and ContainerManager.
 * Also persists container metadata to PostgreSQL via StorageContainerRepository.
 *
 * <p>This service coordinates between:
 * <ul>
 *   <li>The database (Host, StorageContainer entities)</li>
 *   <li>The binary container file (ContainerManager, StorageEngine)</li>
 * </ul>
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final String CONTAINER_FILENAME = "storage.container";

    private final HostRepository hostRepository;
    private final StorageContainerRepository containerRepository;
    private final ContainerManager containerManager;
    private final StorageEngine storageEngine;
    private final StorageProperties storageProperties;

    public StorageService(
            HostRepository hostRepository,
            StorageContainerRepository containerRepository,
            ContainerManager containerManager,
            StorageEngine storageEngine,
            StorageProperties storageProperties) {
        this.hostRepository = hostRepository;
        this.containerRepository = containerRepository;
        this.containerManager = containerManager;
        this.storageEngine = storageEngine;
        this.storageProperties = storageProperties;
    }

    /**
     * Creates a new storage container for a host.
     *
     * @param hostId        the UUID of the host
     * @param size          the reservation size
     * @param containerPath optional client-specified path for the container file;
     *                      if null or blank, uses the default server-side path
     * @return storage status after creation
     */
    @Transactional
    public StorageStatusResponse createStorage(UUID hostId, StorageReservationSize size, String containerPath) {
        log.info("Creating storage container for host {} with size {} (path={})", hostId, size.getDisplayName(), containerPath);

        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found with ID: " + hostId));

        // If container already exists for this host, return active status idempotently
        java.util.Optional<com.neurovault.backend.entity.StorageContainer> existingContainer = containerRepository.findByHostId(hostId);
        if (existingContainer.isPresent()) {
            StorageContainer containerEntity = existingContainer.get();
            log.info("Storage container already exists for host {}, returning active status", hostId);
            ensureContainerOpen(containerEntity);
            return buildStatusResponse(host, containerEntity);
        }

        // Use client-specified path if provided, otherwise use default server-side path
        Path resolvedPath;
        if (containerPath != null && !containerPath.isBlank()) {
            resolvedPath = Paths.get(containerPath);
            // Ensure the path ends with storage.container filename
            if (!resolvedPath.getFileName().toString().endsWith(".container")) {
                resolvedPath = resolvedPath.resolve(CONTAINER_FILENAME);
            }
        } else {
            resolvedPath = resolveContainerPath(hostId);
        }

        // Create the binary container file — this pre-allocates the full size on disk
        containerManager.createContainer(resolvedPath, size.getBytes());

        // Initialize the storage engine
        storageEngine.initialize();

        // Persist container metadata to the database
        StorageContainer containerEntity = StorageContainer.builder()
                .host(host)
                .filePath(resolvedPath.toString())
                .totalSize(size.getBytes())
                .status(StorageContainer.Status.ACTIVE)
                .build();

        containerRepository.save(containerEntity);

        // Update the host's reserved capacity
        host.setReservedCapacityBytes(size.getBytes());
        hostRepository.save(host);

        log.info("Storage container created for host {} at {} ({} bytes locked on disk)",
                hostId, resolvedPath, size.getBytes());

        return buildStatusResponse(host, containerEntity);
    }

    /**
     * Deletes a host's storage container (both file and DB record).
     *
     * @param hostId the UUID of the host
     */
    @Transactional
    public void deleteStorage(UUID hostId) {
        log.info("Deleting storage container for host {}", hostId);

        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found with ID: " + hostId));

        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        Path containerPath = Paths.get(containerEntity.getFilePath());

        // Close and delete the binary container file
        containerManager.deleteContainer(containerPath);

        // Remove from database
        containerRepository.delete(containerEntity);

        // Reset host storage capacity
        host.setReservedCapacityBytes(0L);
        host.setUsedCapacityBytes(0L);
        hostRepository.save(host);

        log.info("Storage container deleted for host {}", hostId);
    }

    /**
     * Returns the storage status of a host's container.
     *
     * @param hostId the UUID of the host
     * @return storage status response
     */
    @Transactional(readOnly = true)
    public StorageStatusResponse getStorageStatus(UUID hostId) {
        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found with ID: " + hostId));

        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        // Ensure the container is open for reading live metrics
        ensureContainerOpen(containerEntity);

        return StorageStatusResponse.builder()
                .containerSizeBytes(containerEntity.getTotalSize())
                .usedSpaceBytes(storageEngine.calculateUsedSpace())
                .freeSpaceBytes(storageEngine.calculateFreeSpace())
                .chunkCount(storageEngine.countActiveChunks())
                .hostStatus(host.getStatus().name())
                .containerStatus(containerEntity.getStatus().name())
                .build();
    }

    /**
     * Stores an encrypted chunk in the host's container.
     *
     * @param hostId  the UUID of the host
     * @param request the chunk data and metadata
     * @return the metadata of the stored chunk
     */
    @Transactional
    public ChunkMetadataDto storeChunk(UUID hostId, StoreChunkRequest request) {
        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(containerEntity);

        ChunkMetadata metadata = storageEngine.storeChunk(
                request.getChunkId(), request.getOwnerId(), request.getData());

        // Update used capacity in the host entity
        Host host = containerEntity.getHost();
        host.setUsedCapacityBytes(storageEngine.calculateUsedSpace());
        hostRepository.save(host);

        return mapToDto(metadata);
    }

    /**
     * Reads an encrypted chunk from the host's container.
     *
     * @param hostId  the UUID of the host
     * @param chunkId the UUID of the chunk
     * @return the raw encrypted bytes
     */
    public byte[] readChunk(UUID hostId, UUID chunkId) {
        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(containerEntity);

        return storageEngine.readChunk(chunkId);
    }

    /**
     * Deletes a chunk from the host's container.
     *
     * @param hostId  the UUID of the host
     * @param chunkId the UUID of the chunk to delete
     */
    @Transactional
    public void deleteChunk(UUID hostId, UUID chunkId) {
        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(containerEntity);

        storageEngine.deleteChunk(chunkId);

        // Update used capacity
        Host host = containerEntity.getHost();
        host.setUsedCapacityBytes(storageEngine.calculateUsedSpace());
        hostRepository.save(host);
    }

    /**
     * Lists all active chunks in the host's container.
     *
     * @param hostId the UUID of the host
     * @return list of chunk metadata DTOs
     */
    public List<ChunkMetadataDto> listChunks(UUID hostId) {
        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(containerEntity);

        return storageEngine.listChunks().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    // ---- Private helpers ----

    /**
     * Resolves the file path for a host's container.
     */
    private Path resolveContainerPath(UUID hostId) {
        return Paths.get(storageProperties.getBaseDir(), hostId.toString(), CONTAINER_FILENAME);
    }

    /**
     * Ensures the container file is open. If not, opens it and re-initializes the engine.
     */
    private void ensureContainerOpen(StorageContainer containerEntity) {
        if (!containerManager.isOpen()) {
            Path path = Paths.get(containerEntity.getFilePath());
            try {
                containerManager.openContainer(path);
                storageEngine.initialize();
            } catch (ContainerException e) {
                throw new ContainerException(
                        "Failed to open container for host " + containerEntity.getHost().getId(), e);
            }
        }
    }

    /**
     * Builds a StorageStatusResponse from entity data and the storage engine's live metrics.
     */
    private StorageStatusResponse buildStatusResponse(Host host, StorageContainer containerEntity) {
        return StorageStatusResponse.builder()
                .containerSizeBytes(containerEntity.getTotalSize())
                .usedSpaceBytes(storageEngine.calculateUsedSpace())
                .freeSpaceBytes(storageEngine.calculateFreeSpace())
                .chunkCount(storageEngine.countActiveChunks())
                .hostStatus(host.getStatus().name())
                .containerStatus(containerEntity.getStatus().name())
                .build();
    }

    /**
     * Maps a ChunkMetadata model to a ChunkMetadataDto.
     */
    private ChunkMetadataDto mapToDto(ChunkMetadata metadata) {
        return ChunkMetadataDto.builder()
                .chunkId(metadata.getChunkId())
                .chunkSize(metadata.getChunkSize())
                .offset(metadata.getOffset())
                .creationTime(metadata.getCreationTime())
                .sha256Hash(metadata.getSha256Hash())
                .checksum(metadata.getChecksum())
                .ownerId(metadata.getOwnerId())
                .build();
    }
}
