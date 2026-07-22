package com.neurovault.backend.host.controller;

import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.host.dto.HeartbeatRequest;
import com.neurovault.backend.host.dto.HeartbeatResponse;
import com.neurovault.backend.host.dto.HostRegistrationRequest;
import com.neurovault.backend.host.dto.HostRegistrationResponse;
import com.neurovault.backend.host.dto.HostStatusDto;
import com.neurovault.backend.host.service.HeartbeatService;
import com.neurovault.backend.host.service.HostRegistrationService;
import com.neurovault.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing Host Agent endpoints for registration, status, and heartbeat.
 * Supports both /api/host and /api/hosts paths for full client compatibility.
 */
@RestController
@RequestMapping({"/api/host", "/api/hosts"})
public class HostController {

    private static final Logger log = LoggerFactory.getLogger(HostController.class);

    private final HostRegistrationService registrationService;
    private final HeartbeatService heartbeatService;
    private final UserRepository userRepository;

    public HostController(
            HostRegistrationService registrationService,
            HeartbeatService heartbeatService,
            UserRepository userRepository) {
        this.registrationService = registrationService;
        this.heartbeatService = heartbeatService;
        this.userRepository = userRepository;
    }

    /**
     * Registers a new host device in the distributed storage network.
     */
    @PostMapping("/register")
    public ResponseEntity<HostRegistrationResponse> registerHost(
            @RequestBody HostRegistrationRequest request,
            Principal principal) {

        UUID ownerId = extractUserId(principal);
        log.info("Host registration request from user {}", ownerId);

        HostRegistrationResponse response = registrationService.registerHost(request, ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Sends a heartbeat for a host node. Resolves host ID automatically if missing.
     */
    @PostMapping({"/heartbeat", "/{hostId}/heartbeat"})
    public ResponseEntity<HeartbeatResponse> receiveHeartbeat(
            @PathVariable(required = false) UUID hostId,
            @RequestBody HeartbeatRequest request,
            Principal principal) {

        UUID targetHostId = hostId != null ? hostId : request.getHostId();
        if (targetHostId == null && principal != null) {
            UUID ownerId = extractUserId(principal);
            List<HostStatusDto> hosts = registrationService.getHostsByOwner(ownerId);
            if (!hosts.isEmpty()) {
                targetHostId = hosts.get(0).getHostId();
            }
        }
        if (targetHostId == null) {
            throw new ResourceNotFoundException("Host ID is required to process heartbeat");
        }

        request.setHostId(targetHostId);
        HeartbeatResponse response = heartbeatService.processHeartbeat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves status of the user's primary/first host node.
     */
    @GetMapping("/status")
    public ResponseEntity<HostStatusDto> getPrimaryHostStatus(Principal principal) {
        UUID ownerId = extractUserId(principal);
        List<HostStatusDto> hosts = registrationService.getHostsByOwner(ownerId);
        if (hosts.isEmpty()) {
            HostStatusDto unregistered = HostStatusDto.builder()
                    .status("UNREGISTERED")
                    .totalCapacityBytes(53687091200L)
                    .reservedCapacityBytes(10737418240L)
                    .usedCapacityBytes(0L)
                    .build();
            return ResponseEntity.ok(unregistered);
        }
        return ResponseEntity.ok(hosts.get(0));
    }

    /**
     * Retrieves details of a specific host by ID.
     */
    @GetMapping("/{hostId}")
    public ResponseEntity<HostStatusDto> getHost(@PathVariable UUID hostId) {
        HostStatusDto host = registrationService.getHostById(hostId);
        return ResponseEntity.ok(host);
    }

    /**
     * Lists all hosts owned by the authenticated user.
     */
    @GetMapping
    public ResponseEntity<List<HostStatusDto>> listHosts(Principal principal) {
        UUID ownerId = extractUserId(principal);
        List<HostStatusDto> hosts = registrationService.getHostsByOwner(ownerId);
        return ResponseEntity.ok(hosts);
    }

    /**
     * Extracts user UUID from principal name (supporting both UUID strings and user email).
     */
    private UUID extractUserId(Principal principal) {
        String name = principal.getName();
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            User user = userRepository.findByEmail(name)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + name));
            return user.getId();
        }
    }
}
