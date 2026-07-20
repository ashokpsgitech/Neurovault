package com.neurovault.backend.host.service;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.host.dto.HostRegistrationRequest;
import com.neurovault.backend.host.dto.HostRegistrationResponse;
import com.neurovault.backend.host.dto.HostStatusDto;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.UserRepository;
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
 * Unit tests for {@link HostRegistrationService}.
 */
@ExtendWith(MockitoExtension.class)
class HostRegistrationServiceTest {

    @Mock
    private HostRepository hostRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private HostRegistrationService registrationService;

    private UUID ownerId;
    private User owner;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        owner = User.builder()
                .id(ownerId)
                .username("testuser")
                .email("test@example.com")
                .password("encoded-password")
                .role(User.Role.HOST)
                .build();
    }

    @Test
    void registerHost_shouldCreateAndReturnResponse() {
        HostRegistrationRequest request = HostRegistrationRequest.builder()
                .hostname("my-laptop")
                .deviceName("Dell XPS 15")
                .operatingSystem("Windows 11")
                .architecture("x86_64")
                .availableStorageBytes(100_000_000_000L)
                .reservedStorageBytes(10_000_000_000L)
                .hostVersion("1.0.0")
                .listeningPort(8081)
                .publicIp("203.0.113.5")
                .localIp("192.168.1.100")
                .build();

        UUID hostId = UUID.randomUUID();
        Host savedHost = Host.builder()
                .id(hostId)
                .owner(owner)
                .name("my-laptop")
                .deviceType("Dell XPS 15")
                .operatingSystem("Windows 11")
                .totalCapacityBytes(100_000_000_000L)
                .reservedCapacityBytes(10_000_000_000L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .heartbeatIntervalSeconds(30)
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(hostRepository.save(any(Host.class))).thenReturn(savedHost);

        HostRegistrationResponse response = registrationService.registerHost(request, ownerId);

        assertNotNull(response);
        assertEquals(hostId, response.getHostId());
        assertEquals("REGISTERED", response.getRegistrationStatus());
        assertEquals(30, response.getHeartbeatIntervalSeconds());

        // Verify the host entity was constructed correctly
        ArgumentCaptor<Host> hostCaptor = ArgumentCaptor.forClass(Host.class);
        verify(hostRepository).save(hostCaptor.capture());
        Host capturedHost = hostCaptor.getValue();
        assertEquals("my-laptop", capturedHost.getName());
        assertEquals(Host.Status.ONLINE, capturedHost.getStatus());
        assertEquals(0L, capturedHost.getUsedCapacityBytes());
    }

    @Test
    void registerHost_shouldThrowWhenUserNotFound() {
        HostRegistrationRequest request = HostRegistrationRequest.builder()
                .hostname("test")
                .deviceName("test")
                .operatingSystem("Linux")
                .architecture("x86_64")
                .availableStorageBytes(1000L)
                .reservedStorageBytes(500L)
                .hostVersion("1.0.0")
                .listeningPort(8080)
                .build();

        UUID unknownUser = UUID.randomUUID();
        when(userRepository.findById(unknownUser)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                registrationService.registerHost(request, unknownUser));

        verify(hostRepository, never()).save(any());
    }

    @Test
    void getHostById_shouldReturnHostStatus() {
        UUID hostId = UUID.randomUUID();
        Host host = Host.builder()
                .id(hostId)
                .name("test-host")
                .deviceType("Desktop")
                .operatingSystem("Ubuntu 22.04")
                .status(Host.Status.ONLINE)
                .totalCapacityBytes(50_000_000_000L)
                .reservedCapacityBytes(10_000_000_000L)
                .usedCapacityBytes(2_000_000_000L)
                .heartbeatIntervalSeconds(30)
                .createdAt(LocalDateTime.now())
                .build();

        when(hostRepository.findById(hostId)).thenReturn(Optional.of(host));

        HostStatusDto status = registrationService.getHostById(hostId);

        assertNotNull(status);
        assertEquals(hostId, status.getHostId());
        assertEquals("test-host", status.getHostname());
        assertEquals("ONLINE", status.getStatus());
    }

    @Test
    void getHostById_shouldThrowWhenNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(hostRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                registrationService.getHostById(unknownId));
    }

    @Test
    void getHostsByOwner_shouldReturnAllOwnerHosts() {
        Host host1 = Host.builder()
                .id(UUID.randomUUID())
                .name("host-1")
                .status(Host.Status.ONLINE)
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(500L)
                .usedCapacityBytes(0L)
                .heartbeatIntervalSeconds(30)
                .createdAt(LocalDateTime.now())
                .build();

        Host host2 = Host.builder()
                .id(UUID.randomUUID())
                .name("host-2")
                .status(Host.Status.OFFLINE)
                .totalCapacityBytes(2000L)
                .reservedCapacityBytes(1000L)
                .usedCapacityBytes(0L)
                .heartbeatIntervalSeconds(30)
                .createdAt(LocalDateTime.now())
                .build();

        when(hostRepository.findByOwnerId(ownerId)).thenReturn(List.of(host1, host2));

        List<HostStatusDto> hosts = registrationService.getHostsByOwner(ownerId);

        assertEquals(2, hosts.size());
        assertEquals("host-1", hosts.get(0).getHostname());
        assertEquals("host-2", hosts.get(1).getHostname());
    }
}
