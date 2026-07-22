package com.neurovault.backend.repository;

import com.neurovault.backend.entity.Host;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

/**
 * Repository interface for Host entity.
 */
@Repository
public interface HostRepository extends JpaRepository<Host, UUID> {
    List<Host> findByOwnerId(UUID ownerId);
    List<Host> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
    List<Host> findByStatus(Host.Status status);
}
