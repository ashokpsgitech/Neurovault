package com.neurovault.backend.repository;

import com.neurovault.backend.entity.HostHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

/**
 * Repository interface for HostHeartbeat entity.
 */
@Repository
public interface HostHeartbeatRepository extends JpaRepository<HostHeartbeat, UUID> {
    List<HostHeartbeat> findByHostIdOrderByTimestampDesc(UUID hostId);
}
