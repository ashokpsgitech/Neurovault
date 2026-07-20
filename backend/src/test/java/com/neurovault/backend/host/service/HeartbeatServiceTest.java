package com.neurovault.backend.host.service;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.HostHeartbeat;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.host.dto.HeartbeatRequest;
import com.neurovault.backend.host.dto.HeartbeatResponse;
import com.neurovault.backend.repository.HostHeartbeatRepository;
import com.neurovault.backend.repository.HostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HeartbeatService}.
 */
@ExtendWith(MockitoExtension.class)
class HeartbeatServiceTest {

    @Mock
    private HostRepository hostRepository;

    @Mock
    private HostHeartbeatRepository heartbeatRepository;

    @InjectMocks
    private HeartbeatService heartbeatService;

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
                .reservedCapacityBytes(5_000_000_000L)
                .usedCapacityBytes(1_000_000_000L)
                .heartbeatIntervalSeconds(30)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void processHeartbeat_shouldUpdateHostAndPersist() {
        HeartbeatRequest request = HeartbeatRequest.builder()
                .hostId(hostId)
                .timestamp(LocalDateTime.now())
                .cpuUsagePercent(45.5)
                .ramUsagePercent(67.2)
                .reservedStorageBytes(5_000_000_000L)
                .usedStorageBytes(2_000_000_000L)
                .availableStorageBytes(3_000_000_000L)
                .hostStatus("ONLINE")
                .containerStatus("ACTIVE")
                .build();

        when(hostRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(hostRepository.save(any(Host.class))).thenReturn(host);
        when(heartbeatRepository.save(any(HostHeartbeat.class))).thenReturn(
                HostHeartbeat.builder().id(UUID.randomUUID()).build());

        HeartbeatResponse response = heartbeatService.processHeartbeat(request);

        assertTrue(response.isAcknowledged());
        assertEquals(30, response.getNextHeartbeatSeconds());
        assertNotNull(response.getServerTimestamp());

        // Verify host was updated
        ArgumentCaptor<Host> hostCaptor = ArgumentCaptor.forClass(Host.class);
        verify(hostRepository).save(hostCaptor.capture());
        Host savedHost = hostCaptor.getValue();
        assertEquals(Host.Status.ONLINE, savedHost.getStatus());
        assertEquals(2_000_000_000L, savedHost.getUsedCapacityBytes());

        // Verify heartbeat was persisted
        ArgumentCaptor<HostHeartbeat> heartbeatCaptor = ArgumentCaptor.forClass(HostHeartbeat.class);
        verify(heartbeatRepository).save(heartbeatCaptor.capture());
        HostHeartbeat savedHeartbeat = heartbeatCaptor.getValue();
        assertEquals(45.5, savedHeartbeat.getCpuUsagePercent());
        assertEquals(67.2, savedHeartbeat.getMemoryUsagePercent());
    }

    @Test
    void processHeartbeat_shouldThrowWhenHostNotFound() {
        HeartbeatRequest request = HeartbeatRequest.builder()
                .hostId(UUID.randomUUID())
                .timestamp(LocalDateTime.now())
                .hostStatus("ONLINE")
                .build();

        when(hostRepository.findById(request.getHostId())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                heartbeatService.processHeartbeat(request));

        verify(heartbeatRepository, never()).save(any());
    }

    @Test
    void getHeartbeatHistory_shouldReturnOrderedList() {
        HostHeartbeat hb1 = HostHeartbeat.builder()
                .id(UUID.randomUUID())
                .host(host)
                .timestamp(LocalDateTime.now().minusMinutes(2))
                .cpuUsagePercent(30.0)
                .status(Host.Status.ONLINE)
                .build();

        HostHeartbeat hb2 = HostHeartbeat.builder()
                .id(UUID.randomUUID())
                .host(host)
                .timestamp(LocalDateTime.now())
                .cpuUsagePercent(50.0)
                .status(Host.Status.ONLINE)
                .build();

        when(hostRepository.existsById(hostId)).thenReturn(true);
        when(heartbeatRepository.findByHostIdOrderByTimestampDesc(hostId))
                .thenReturn(List.of(hb2, hb1));

        List<HostHeartbeat> history = heartbeatService.getHeartbeatHistory(hostId);

        assertEquals(2, history.size());
        assertEquals(50.0, history.get(0).getCpuUsagePercent());
        assertEquals(30.0, history.get(1).getCpuUsagePercent());
    }

    @Test
    void getHeartbeatHistory_shouldThrowWhenHostNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(hostRepository.existsById(unknownId)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () ->
                heartbeatService.getHeartbeatHistory(unknownId));
    }

    @Test
    void markHostOffline_shouldUpdateStatus() {
        when(hostRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(hostRepository.save(any(Host.class))).thenReturn(host);

        heartbeatService.markHostOffline(hostId);

        ArgumentCaptor<Host> captor = ArgumentCaptor.forClass(Host.class);
        verify(hostRepository).save(captor.capture());
        assertEquals(Host.Status.OFFLINE, captor.getValue().getStatus());
    }

    @Test
    void markHostOffline_shouldNotUpdateIfAlreadyOffline() {
        host.setStatus(Host.Status.OFFLINE);
        when(hostRepository.findById(hostId)).thenReturn(Optional.of(host));

        heartbeatService.markHostOffline(hostId);

        // Should not save if already offline
        verify(hostRepository, never()).save(any());
    }
}
