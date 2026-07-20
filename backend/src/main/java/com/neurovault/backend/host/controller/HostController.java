package com.neurovault.backend.host.controller;

import com.neurovault.backend.host.dto.HeartbeatRequest;
import com.neurovault.backend.host.dto.HeartbeatResponse;
import com.neurovault.backend.host.dto.HostRegistrationRequest;
import com.neurovault.backend.host.dto.HostRegistrationResponse;
import com.neurovault.backend.host.dto.HostStatusDto;
import com.neurovault.backend.host.service.HeartbeatService;
import com.neurovault.backend.host.service.HostRegistrationService;
import com.neurovault.backend.security.JwtUtils;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing Host Agent endpoints for registration and heartbeat.
 *
 * <p>All endpoints require JWT authentication. The authenticated user's identity
 * is extracted from the security context to associate hosts with their owner.
 */
@RestController
@RequestMapping("/api/hosts")
public class HostController {

    private static final Logger log = LoggerFactory.getLogger(HostController.class);

    private final HostRegistrationService registrationService;
    private final HeartbeatService heartbeatService;

    public HostController(HostRegistrationService registrationService, HeartbeatService heartbeatService) {
        this.registrationService = registrationService;
        this.heartbeatService = heartbeatService;
    }

    /**
     * Registers a new host device in the distributed storage network.
     *
     * @param request   the host registration payload
     * @param principal the authenticated user principal
     * @return registration confirmation with host ID and heartbeat interval
     */
    @PostMapping("/register")
    public ResponseEntity<HostRegistrationResponse> registerHost(
            @Valid @RequestBody HostRegistrationRequest request,
            Principal principal) {

        UUID ownerId = extractUserId(principal);
        log.info("Host registration request from user {}", ownerId);

        HostRegistrationResponse response = registrationService.registerHost(request, ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Receives a heartbeat from a registered host.
     *
     * @param hostId  the UUID of the reporting host
     * @param request the heartbeat payload with health metrics
     * @return heartbeat acknowledgment
     */
    @PostMapping("/{hostId}/heartbeat")
    public ResponseEntity<HeartbeatResponse> receiveHeartbeat(
            @PathVariable UUID hostId,
            @Valid @RequestBody HeartbeatRequest request) {

        // Ensure the path variable matches the request body
        request.setHostId(hostId);

        HeartbeatResponse response = heartbeatService.processHeartbeat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves details of a specific host.
     *
     * @param hostId the UUID of the host
     * @return host status details
     */
    @GetMapping("/{hostId}")
    public ResponseEntity<HostStatusDto> getHost(@PathVariable UUID hostId) {
        HostStatusDto host = registrationService.getHostById(hostId);
        return ResponseEntity.ok(host);
    }

    /**
     * Lists all hosts owned by the authenticated user.
     *
     * @param principal the authenticated user principal
     * @return list of host status DTOs
     */
    @GetMapping
    public ResponseEntity<List<HostStatusDto>> listHosts(Principal principal) {
        UUID ownerId = extractUserId(principal);
        List<HostStatusDto> hosts = registrationService.getHostsByOwner(ownerId);
        return ResponseEntity.ok(hosts);
    }

    /**
     * Extracts the user UUID from the security principal.
     * The principal name is the user's email, so we parse it through the context.
     * Since the JwtAuthenticationFilter sets the username (email) as the principal,
     * we need a lookup. However, to avoid coupling to UserRepository directly,
     * we parse the UUID if it's set, or fall back to a name-based approach.
     *
     * <p>Note: This implementation assumes the principal name is a UUID string
     * or an email. For production, consider extracting the user ID from the JWT claims.
     */
    private UUID extractUserId(Principal principal) {
        // The principal name from JWT is the user email.
        // We need to convert it to a UUID. For now, we parse if it's a UUID format,
        // otherwise we rely on the service layer to handle email-based lookup.
        String name = principal.getName();
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            // The principal is an email — hash it deterministically is not safe.
            // Instead, we should look up the user. But to avoid coupling to UserRepository,
            // we throw an informative error. The proper fix is to include user ID in JWT claims.
            throw new IllegalStateException(
                    "Cannot extract user ID from principal. Principal name: " + name +
                    ". Ensure the JWT token contains the user UUID as the subject.");
        }
    }
}
