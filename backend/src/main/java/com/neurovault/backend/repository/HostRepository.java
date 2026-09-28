package com.neurovault.backend.repository;

import com.neurovault.backend.entity.Host;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Atomically reserves capacity on a host if and only if sufficient unreserved, unused capacity exists (NV-P1-03).
     * Returns 1 if reservation succeeded, 0 if capacity was insufficient.
     */
    @Modifying
    @Query("UPDATE Host h SET h.reservedCapacityBytes = h.reservedCapacityBytes + :size " +
           "WHERE h.id = :hostId AND (h.totalCapacityBytes - h.usedCapacityBytes - h.reservedCapacityBytes) >= :size")
    int reserveCapacityAtomic(@Param("hostId") UUID hostId, @Param("size") long size);

    /**
     * Atomically releases a previously held capacity reservation.
     */
    @Modifying
    @Query("UPDATE Host h SET h.reservedCapacityBytes = CASE WHEN h.reservedCapacityBytes >= :size THEN h.reservedCapacityBytes - :size ELSE 0 END " +
           "WHERE h.id = :hostId")
    int releaseCapacityReservation(@Param("hostId") UUID hostId, @Param("size") long size);

    /**
     * Atomically moves capacity from reserved to used upon successful physical chunk commit.
     */
    @Modifying
    @Query("UPDATE Host h SET h.reservedCapacityBytes = CASE WHEN h.reservedCapacityBytes >= :size THEN h.reservedCapacityBytes - :size ELSE 0 END, " +
           "h.usedCapacityBytes = h.usedCapacityBytes + :size WHERE h.id = :hostId")
    int commitReservedCapacity(@Param("hostId") UUID hostId, @Param("size") long size);
}
