package com.neurovault.backend.coordinator;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.exception.BadRequestException;
import com.neurovault.backend.replication.service.HostSelectionStrategy;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.security.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Control Plane Coordinator Service responsible for host selection algorithms,
 * load balancing chunk placement across online host nodes, and issuing signed tokens.
 */
@Service
public class CoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorService.class);

    private final HostRepository hostRepository;
    private final HostSelectionStrategy hostSelectionStrategy;
    private final JwtUtils jwtUtils;

    public CoordinatorService(
            HostRepository hostRepository,
            HostSelectionStrategy hostSelectionStrategy,
            JwtUtils jwtUtils) {
        this.hostRepository = hostRepository;
        this.hostSelectionStrategy = hostSelectionStrategy;
        this.jwtUtils = jwtUtils;
    }

    /**
     * Selects online host devices available to store chunk blocks using the weighted score host placement strategy.
     *
     * @param totalChunks required number of chunk allocations
     * @return list of active online hosts
     */
    public List<Host> selectTargetHosts(int totalChunks) {
        List<Host> selected = hostSelectionStrategy.selectHosts(
                totalChunks, 4194304L, Collections.emptySet());

        if (selected.isEmpty()) {
            List<Host> onlineHosts = hostRepository.findAll().stream()
                    .filter(h -> h.getStatus() == Host.Status.ONLINE)
                    .collect(Collectors.toList());

            if (onlineHosts.isEmpty()) {
                throw new BadRequestException("No online host nodes currently available in the storage network.");
            }
            selected = onlineHosts;
        }

        log.info("Selected {} target host nodes using strategy '{}' for {} chunk allocations",
                selected.size(), hostSelectionStrategy.getStrategyName(), totalChunks);
        return selected;
    }

    /**
     * Generates a signed authorization token for a client to perform a direct chunk operation on a host.
     *
     * @param sessionId upload or download session ID
     * @param hostId    target host ID
     * @param chunkIndex chunk index
     * @return signed token string
     */
    public String generateChunkToken(UUID sessionId, UUID hostId, int chunkIndex) {
        String tokenSubject = String.format("chunk-session:%s:host:%s:index:%d", sessionId, hostId, chunkIndex);
        return jwtUtils.generateToken(tokenSubject);
    }
}
