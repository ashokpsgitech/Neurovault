package com.neurovault.backend.repository;

import com.neurovault.backend.entity.StorageContainer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for StorageContainer entity.
 */
@Repository
public interface StorageContainerRepository extends JpaRepository<StorageContainer, UUID> {
    Optional<StorageContainer> findByHostId(UUID hostId);
}
