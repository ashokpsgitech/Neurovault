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
import com.neurovault.backend.storage.model.ChunkMetadata;
import com.neurovault.backend.storage.model.StorageReservationSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StorageService}.
 */
@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    @Mock
    private HostRepository hostRepository;

    @Mock
    private StorageContainerRepository containerRepository;

    @Mock
    private ContainerManager containerManager;

    @Mock
    private StorageEngine storageEngine;

    @Mock
    private StorageProperties storageProperties;

    @InjectMocks
    private StorageService storageService;

    private UUID hostId;
    private Host host;

    @BeforeEach
    void setUp() {
        hostId = UUID.randomUUID();
        host = Host.builder()
                .id(hostId)
                .name("test-host")
                .status(Host.Status.ONLINE)
                .totalCapacityBytes(10_000_000_000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .heartbeatIntervalSeconds(30)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void createStorage_shouldCreateContainerAndPersist() {
        when(hostRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(containerRepository.findByHostId(hostId)).thenReturn(Optional.empty());
        when(storageProperties.getBaseDir()).thenReturn("./test-storage");
        when(containerRepository.save(any(StorageContainer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(hostRepository.save(any(Host.class))).thenReturn(host);
        when(storageEngine.calculateUsedSpace()).thenReturn(0L);
        when(storageEngine.calculateFreeSpace()).thenReturn(StorageReservationSize.GB_1.getBytes());
        when(storageEngine.countActiveChunks()).thenReturn(0);

        StorageStatusResponse response = storageService.createStorage(hostId, StorageReservationSize.GB_1);

        assertNotNull(response);
        assertEquals(StorageReservationSize.GB_1.getBytes(), response.getContainerSizeBytes());
        assertEquals(0, response.getUsedSpaceBytes());
        assertEquals("ONLINE", response.getHostStatus());
        assertEquals("ACTIVE", response.getContainerStatus());

        verify(containerManager).createContainer(any(), eq(StorageReservationSize.GB_1.getBytes()));
        verify(storageEngine).initialize();
        verify(containerRepository).save(any(StorageContainer.class));
    }

    @Test
    void createStorage_shouldRejectDuplicateContainer() {
        when(hostRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(containerRepository.findByHostId(hostId)).thenReturn(Optional.of(
                StorageContainer.builder().id(UUID.randomUUID()).build()));

        assertThrows(BadRequestException.class, () ->
                storageService.createStorage(hostId, StorageReservationSize.GB_1));

        verify(containerManager, never()).createContainer(any(), anyLong());
    }

    @Test
    void createStorage_shouldThrowWhenHostNotFound() {
        when(hostRepository.findById(hostId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                storageService.createStorage(hostId, StorageReservationSize.GB_1));
    }

    @Test
    void deleteStorage_shouldDeleteContainerAndCleanup() {
        StorageContainer containerEntity = StorageContainer.builder()
                .id(UUID.randomUUID())
                .host(host)
                .filePath("./test-storage/" + hostId + "/storage.container")
                .totalSize(StorageReservationSize.GB_1.getBytes())
                .status(StorageContainer.Status.ACTIVE)
                .build();

        when(hostRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(containerRepository.findByHostId(hostId)).thenReturn(Optional.of(containerEntity));
        when(hostRepository.save(any(Host.class))).thenReturn(host);

        storageService.deleteStorage(hostId);

        verify(containerManager).deleteContainer(any());
        verify(containerRepository).delete(containerEntity);
        verify(hostRepository).save(any(Host.class));
    }

    @Test
    void getStorageStatus_shouldReturnLiveMetrics() {
        StorageContainer containerEntity = StorageContainer.builder()
                .id(UUID.randomUUID())
                .host(host)
                .filePath("./test-storage/" + hostId + "/storage.container")
                .totalSize(StorageReservationSize.GB_1.getBytes())
                .status(StorageContainer.Status.ACTIVE)
                .build();

        when(hostRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(containerRepository.findByHostId(hostId)).thenReturn(Optional.of(containerEntity));
        when(containerManager.isOpen()).thenReturn(true);
        when(storageEngine.calculateUsedSpace()).thenReturn(500_000L);
        when(storageEngine.calculateFreeSpace()).thenReturn(StorageReservationSize.GB_1.getBytes() - 500_000L);
        when(storageEngine.countActiveChunks()).thenReturn(10);

        StorageStatusResponse status = storageService.getStorageStatus(hostId);

        assertEquals(StorageReservationSize.GB_1.getBytes(), status.getContainerSizeBytes());
        assertEquals(500_000L, status.getUsedSpaceBytes());
        assertEquals(10, status.getChunkCount());
    }

    @Test
    void storeChunk_shouldDelegateToEngine() {
        UUID chunkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] data = "encrypted-data".getBytes();

        StorageContainer containerEntity = StorageContainer.builder()
                .id(UUID.randomUUID())
                .host(host)
                .filePath("./test-storage/" + hostId + "/storage.container")
                .totalSize(StorageReservationSize.GB_1.getBytes())
                .status(StorageContainer.Status.ACTIVE)
                .build();

        ChunkMetadata metadata = new ChunkMetadata(
                chunkId, data.length, 1024, Instant.now(), "abc123", 12345L, ownerId);

        when(containerRepository.findByHostId(hostId)).thenReturn(Optional.of(containerEntity));
        when(containerManager.isOpen()).thenReturn(true);
        when(storageEngine.storeChunk(chunkId, ownerId, data)).thenReturn(metadata);
        when(storageEngine.calculateUsedSpace()).thenReturn((long) data.length);
        when(hostRepository.save(any(Host.class))).thenReturn(host);

        StoreChunkRequest request = StoreChunkRequest.builder()
                .chunkId(chunkId)
                .ownerId(ownerId)
                .data(data)
                .build();

        ChunkMetadataDto result = storageService.storeChunk(hostId, request);

        assertNotNull(result);
        assertEquals(chunkId, result.getChunkId());
        verify(storageEngine).storeChunk(chunkId, ownerId, data);
    }

    @Test
    void readChunk_shouldReturnEncryptedBytes() {
        UUID chunkId = UUID.randomUUID();
        byte[] expectedData = "encrypted-bytes".getBytes();

        StorageContainer containerEntity = StorageContainer.builder()
                .id(UUID.randomUUID())
                .host(host)
                .filePath("./test-storage/" + hostId + "/storage.container")
                .totalSize(StorageReservationSize.GB_1.getBytes())
                .status(StorageContainer.Status.ACTIVE)
                .build();

        when(containerRepository.findByHostId(hostId)).thenReturn(Optional.of(containerEntity));
        when(containerManager.isOpen()).thenReturn(true);
        when(storageEngine.readChunk(chunkId)).thenReturn(expectedData);

        byte[] result = storageService.readChunk(hostId, chunkId);
        assertArrayEquals(expectedData, result);
    }

    @Test
    void listChunks_shouldReturnDtoList() {
        StorageContainer containerEntity = StorageContainer.builder()
                .id(UUID.randomUUID())
                .host(host)
                .filePath("./test-storage/" + hostId + "/storage.container")
                .totalSize(StorageReservationSize.GB_1.getBytes())
                .status(StorageContainer.Status.ACTIVE)
                .build();

        ChunkMetadata meta1 = new ChunkMetadata(
                UUID.randomUUID(), 100, 1024, Instant.now(), "hash1", 111L, UUID.randomUUID());
        ChunkMetadata meta2 = new ChunkMetadata(
                UUID.randomUUID(), 200, 2048, Instant.now(), "hash2", 222L, UUID.randomUUID());

        when(containerRepository.findByHostId(hostId)).thenReturn(Optional.of(containerEntity));
        when(containerManager.isOpen()).thenReturn(true);
        when(storageEngine.listChunks()).thenReturn(List.of(meta1, meta2));

        List<ChunkMetadataDto> result = storageService.listChunks(hostId);

        assertEquals(2, result.size());
        assertEquals(meta1.getChunkId(), result.get(0).getChunkId());
        assertEquals(meta2.getChunkId(), result.get(1).getChunkId());
    }
}
