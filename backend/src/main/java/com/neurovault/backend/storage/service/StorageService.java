package com.neurovault.backend.storage.service;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.StorageContainer;
import com.neurovault.backend.exception.BadRequestException;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.StorageContainerRepository;
import com.neurovault.backend.storage.config.StorageProperties;
import com.neurovault.backend.storage.container.ContainerContext;
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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service bridging REST controllers and data plane services with StorageEngine and ContainerManager.
 * Manages multi-host container concurrency and coordinates with PostgreSQL metadata.
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
     */
    @Transactional
    public StorageStatusResponse createStorage(UUID hostId, StorageReservationSize size, String containerPath) {
        log.info("Creating storage container for host {} with size {} (path={})", hostId, size.getDisplayName(), containerPath);

        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found with ID: " + hostId));

        Path resolvedPath;
        if (containerPath != null && !containerPath.isBlank()) {
            Path candidate = Paths.get(containerPath).normalize().toAbsolutePath();
            Path baseDir = Paths.get(storageProperties.getBaseDir()).normalize().toAbsolutePath();
            if (candidate.startsWith(baseDir)) {
                Path fileName = candidate.getFileName();
                if (fileName == null || !fileName.toString().endsWith(".container")) {
                    candidate = candidate.resolve(CONTAINER_FILENAME);
                }
                resolvedPath = candidate;
            } else {
                log.warn("Rejected path traversal attempt '{}'; enforcing secure server-managed path", containerPath);
                resolvedPath = resolveContainerPath(hostId);
            }
        } else {
            resolvedPath = resolveContainerPath(hostId);
        }

        if (!Files.exists(resolvedPath)) {
            log.info("Creating disk container file at: {}", resolvedPath);
            containerManager.createContainer(hostId, resolvedPath, size.getBytes());
            storageEngine.initialize(hostId);
        } else {
            log.info("Disk container file already exists at: {}, opening container", resolvedPath);
            ensureContainerOpen(hostId, resolvedPath);
        }

        StorageContainer containerEntity;
        java.util.Optional<StorageContainer> existingContainer = containerRepository.findByHostId(hostId);
        if (existingContainer.isPresent()) {
            containerEntity = existingContainer.get();
            containerEntity.setFilePath(resolvedPath.toString());
            containerEntity.setTotalSize(size.getBytes());
            containerEntity.setStatus(StorageContainer.Status.ACTIVE);
        } else {
            containerEntity = StorageContainer.builder()
                    .host(host)
                    .filePath(resolvedPath.toString())
                    .totalSize(size.getBytes())
                    .status(StorageContainer.Status.ACTIVE)
                    .build();
        }

        containerRepository.save(containerEntity);

        host.setReservedCapacityBytes(size.getBytes());
        hostRepository.save(host);

        log.info("Storage container created for host {} at {} ({} bytes locked on disk)",
                hostId, resolvedPath, size.getBytes());

        return buildStatusResponse(hostId, host, containerEntity);
    }

    /**
     * Deletes a host's storage container.
     */
    @Transactional
    public void deleteStorage(UUID hostId) {
        log.info("Deleting storage container for host {}", hostId);

        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found with ID: " + hostId));

        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        Path containerPath = Paths.get(containerEntity.getFilePath());
        containerManager.deleteContainer(hostId, containerPath);
        containerRepository.delete(containerEntity);

        host.setReservedCapacityBytes(0L);
        host.setUsedCapacityBytes(0L);
        hostRepository.save(host);

        log.info("Storage container deleted for host {}", hostId);
    }

    /**
     * Returns the storage status of a host's container.
     */
    @Transactional(readOnly = true)
    public StorageStatusResponse getStorageStatus(UUID hostId) {
        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found with ID: " + hostId));

        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(hostId, containerEntity);

        return buildStatusResponse(hostId, host, containerEntity);
    }

    /**
     * Stores an encrypted chunk in the host's container.
     */
    @Transactional
    public ChunkMetadataDto storeChunk(UUID hostId, StoreChunkRequest request) {
        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(hostId, containerEntity);

        UUID ownerId = request.getOwnerId() != null
                ? request.getOwnerId()
                : (containerEntity.getHost().getOwner() != null ? containerEntity.getHost().getOwner().getId() : UUID.randomUUID());

        ChunkMetadata metadata = storageEngine.storeChunk(
                hostId, request.getChunkId(), ownerId, request.getData());

        Host host = containerEntity.getHost();
        host.setUsedCapacityBytes(storageEngine.calculateUsedSpace(hostId));
        hostRepository.save(host);

        return mapToDto(metadata);
    }

    /**
     * Stores a chunk via bounded streaming without loading into memory.
     */
    @Transactional
    public ChunkMetadataDto storeChunkStream(UUID hostId, UUID chunkId, UUID ownerId,
                                            InputStream in, long size, String expectedSha256) {
        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(hostId, containerEntity);

        ChunkMetadata metadata = storageEngine.storeChunkStream(
                hostId, chunkId, ownerId, in, size, expectedSha256);

        Host host = containerEntity.getHost();
        host.setUsedCapacityBytes(storageEngine.calculateUsedSpace(hostId));
        hostRepository.save(host);

        return mapToDto(metadata);
    }

    /**
     * Reads an encrypted chunk from the host's container.
     */
    public byte[] readChunk(UUID hostId, UUID chunkId) {
        if (hostId == null) {
            throw new BadRequestException("hostId is required to read a chunk");
        }

        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(hostId, containerEntity);

        return storageEngine.readChunk(hostId, chunkId);
    }

    /**
     * Opens a read stream for a chunk.
     */
    public InputStream readChunkStream(UUID hostId, UUID chunkId) {
        if (hostId == null) {
            throw new BadRequestException("hostId is required to read a chunk");
        }

        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(hostId, containerEntity);

        return storageEngine.readChunkStream(hostId, chunkId);
    }

    /**
     * Deletes a chunk from the host's container.
     */
    @Transactional
    public void deleteChunk(UUID hostId, UUID chunkId) {
        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(hostId, containerEntity);

        storageEngine.deleteChunk(hostId, chunkId);

        Host host = containerEntity.getHost();
        host.setUsedCapacityBytes(storageEngine.calculateUsedSpace(hostId));
        hostRepository.save(host);
    }

    /**
     * Lists all active chunks in the host's container.
     */
    public List<ChunkMetadataDto> listChunks(UUID hostId) {
        StorageContainer containerEntity = containerRepository.findByHostId(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("No storage container found for host: " + hostId));

        ensureContainerOpen(hostId, containerEntity);

        return storageEngine.listChunks(hostId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    public boolean verifyChunkIntegrity(UUID hostId, UUID chunkId, String expectedSha256) {
        return storageEngine.verifyChunkIntegrity(hostId, chunkId, expectedSha256);
    }

    // ---- Private helpers ----

    private Path resolveContainerPath(UUID hostId) {
        return Paths.get(storageProperties.getBaseDir(), hostId.toString(), CONTAINER_FILENAME);
    }

    public void ensureContainerOpen(UUID hostId, StorageContainer containerEntity) {
        ensureContainerOpen(hostId, Paths.get(containerEntity.getFilePath()));
    }

    public void ensureContainerOpen(UUID hostId, Path path) {
        ContainerContext ctx = containerManager.getContext(hostId);
        if (ctx == null || !ctx.isOpen()) {
            try {
                containerManager.openContainer(hostId, path);
                storageEngine.initialize(hostId);
            } catch (ContainerException e) {
                throw new ContainerException("Failed to open container for host " + hostId + " at " + path, e);
            }
        }
    }

    private StorageStatusResponse buildStatusResponse(UUID hostId, Host host, StorageContainer containerEntity) {
        return StorageStatusResponse.builder()
                .containerSizeBytes(containerEntity.getTotalSize())
                .usedSpaceBytes(storageEngine.calculateUsedSpace(hostId))
                .freeSpaceBytes(storageEngine.calculateFreeSpace(hostId))
                .chunkCount(storageEngine.countActiveChunks(hostId))
                .hostStatus(host.getStatus().name())
                .containerStatus(containerEntity.getStatus().name())
                .build();
    }

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
