package com.neurovault.backend.storage.dto;

import com.neurovault.backend.storage.model.StorageReservationSize;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO for creating a new storage container on a host.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateContainerRequest {

    @NotNull(message = "Host ID is required")
    private UUID hostId;

    @NotNull(message = "Reservation size is required")
    private StorageReservationSize reservationSize;
}
