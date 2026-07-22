package com.neurovault.backend.host.service;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.host.dto.HostRegistrationRequest;
import com.neurovault.backend.host.dto.HostRegistrationResponse;
import com.neurovault.backend.host.dto.HostStatusDto;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service responsible for host registration and host lifecycle management.
 * When a Host Agent starts, it sends its device metadata to the coordinator.
 * This service persists the host record and returns registration confirmation.
 */
@Service
public class HostRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(HostRegistrationService.class);

    private final HostRepository hostRepository;
    private final UserRepository userRepository;

    public HostRegistrationService(HostRepository hostRepository, UserRepository userRepository) {
        this.hostRepository = hostRepository;
        this.userRepository = userRepository;
    }

    /**
     * Registers a new host device in the distributed storage network.
     *
     * @param request  the registration payload from the Host Agent
     * @param ownerId  the UUID of the authenticated user who owns this host
     * @return registration confirmation with assigned host ID and heartbeat interval
     */
    @Transactional
    public HostRegistrationResponse registerHost(HostRegistrationRequest request, UUID ownerId) {
        log.info("Registering new host '{}' for owner {}", request.getHostname(), ownerId);

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + ownerId));

        Host host = Host.builder()
                .owner(owner)
                .name(request.getHostname())
                .deviceType(request.getDeviceName())
                .operatingSystem(request.getOperatingSystem())
                .publicIp(request.getPublicIp())
                .totalCapacityBytes(request.getAvailableStorageBytes())
                .reservedCapacityBytes(request.getReservedStorageBytes())
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .heartbeatIntervalSeconds(30)
                .build();

        Host savedHost = hostRepository.save(host);

        log.info("Host '{}' registered successfully with ID {}", savedHost.getName(), savedHost.getId());

        return HostRegistrationResponse.builder()
                .hostId(savedHost.getId())
                .registrationStatus("REGISTERED")
                .heartbeatIntervalSeconds(savedHost.getHeartbeatIntervalSeconds())
                .registeredAt(savedHost.getCreatedAt())
                .build();
    }

    /**
     * Retrieves a host by its ID.
     *
     * @param hostId the UUID of the host
     * @return host status details
     * @throws ResourceNotFoundException if the host does not exist
     */
    @Transactional(readOnly = true)
    public HostStatusDto getHostById(UUID hostId) {
        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found with ID: " + hostId));
        return mapToDto(host);
    }

    /**
     * Lists all hosts owned by a specific user.
     *
     * @param ownerId the UUID of the owner
     * @return list of host status DTOs
     */
    @Transactional(readOnly = true)
    public List<HostStatusDto> getHostsByOwner(UUID ownerId) {
        List<Host> hosts = hostRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        return hosts.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    /**
     * Maps a Host entity to a HostStatusDto.
     */
    private HostStatusDto mapToDto(Host host) {
        return HostStatusDto.builder()
                .hostId(host.getId())
                .hostname(host.getName())
                .deviceName(host.getDeviceType())
                .operatingSystem(host.getOperatingSystem())
                .status(host.getStatus().name())
                .lastHeartbeat(host.getLastHeartbeat())
                .totalCapacityBytes(host.getTotalCapacityBytes())
                .reservedCapacityBytes(host.getReservedCapacityBytes())
                .usedCapacityBytes(host.getUsedCapacityBytes())
                .heartbeatIntervalSeconds(host.getHeartbeatIntervalSeconds())
                .createdAt(host.getCreatedAt())
                .build();
    }
}
