package com.neurovault.backend.host.service;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.HostHeartbeat;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.host.dto.HeartbeatRequest;
import com.neurovault.backend.host.dto.HeartbeatResponse;
import com.neurovault.backend.repository.HostHeartbeatRepository;
import com.neurovault.backend.repository.HostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service responsible for processing heartbeat reports from Host Agents.
 * Each heartbeat updates the host's last-seen timestamp and records
 * system health metrics (CPU, RAM) and storage usage in the database.
 */
@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private final HostRepository hostRepository;
    private final HostHeartbeatRepository heartbeatRepository;

    public HeartbeatService(HostRepository hostRepository, HostHeartbeatRepository heartbeatRepository) {
        this.hostRepository = hostRepository;
        this.heartbeatRepository = heartbeatRepository;
    }

    /**
     * Processes a heartbeat from a Host Agent.
     * Updates the host's last heartbeat timestamp, status, and storage usage.
     * Persists the heartbeat record for historical monitoring.
     *
     * @param request the heartbeat payload
     * @return acknowledgment with the next heartbeat interval
     * @throws ResourceNotFoundException if the host ID is not registered
     */
    @Transactional
    public HeartbeatResponse processHeartbeat(HeartbeatRequest request) {
        UUID hostId = request.getHostId();
        log.debug("Processing heartbeat from host {}", hostId);

        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found with ID: " + hostId));

        // Update host status and last heartbeat timestamp
        host.setLastHeartbeat(request.getTimestamp());
        host.setStatus(Host.Status.ONLINE);

        if (request.getUsedStorageBytes() != null) {
            host.setUsedCapacityBytes(request.getUsedStorageBytes());
        }

        hostRepository.save(host);

        // Persist the heartbeat record
        HostHeartbeat heartbeat = HostHeartbeat.builder()
                .host(host)
                .timestamp(request.getTimestamp())
                .cpuUsagePercent(request.getCpuUsagePercent())
                .memoryUsagePercent(request.getRamUsagePercent())
                .storageUsedBytes(request.getUsedStorageBytes())
                .status(Host.Status.ONLINE)
                .build();

        heartbeatRepository.save(heartbeat);

        log.debug("Heartbeat from host {} processed successfully", hostId);

        return HeartbeatResponse.builder()
                .acknowledged(true)
                .nextHeartbeatSeconds(host.getHeartbeatIntervalSeconds())
                .serverTimestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Retrieves heartbeat history for a host, ordered by timestamp descending.
     *
     * @param hostId the UUID of the host
     * @return list of heartbeat records
     */
    @Transactional(readOnly = true)
    public List<HostHeartbeat> getHeartbeatHistory(UUID hostId) {
        if (!hostRepository.existsById(hostId)) {
            throw new ResourceNotFoundException("Host not found with ID: " + hostId);
        }
        return heartbeatRepository.findByHostIdOrderByTimestampDesc(hostId);
    }

    /**
     * Marks a host as OFFLINE. Called by the HeartbeatScheduler when a host
     * has missed too many consecutive heartbeats.
     *
     * @param hostId the UUID of the host to mark offline
     */
    @Transactional
    public void markHostOffline(UUID hostId) {
        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found with ID: " + hostId));

        if (host.getStatus() == Host.Status.ONLINE) {
            log.warn("Marking host {} ('{}') as OFFLINE due to missed heartbeats", hostId, host.getName());
            host.setStatus(Host.Status.OFFLINE);
            hostRepository.save(host);
        }
    }
}
