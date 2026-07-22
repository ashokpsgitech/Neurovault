package com.neurovault.backend.storage.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.neurovault.backend.storage.model.StorageReservationSize;
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

    @JsonAlias({"hostId", "id"})
    private UUID hostId;

    @JsonAlias({"reservationSize", "reservationGb", "size"})
    private StorageReservationSize reservationSize;

    private String containerPath;
}
