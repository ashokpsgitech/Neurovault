package com.neurovault.backend.replication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for {@code POST /api/cluster/repair}.
 *
 * <p>Summarises the results of a manual or scheduled repair operation.</p>
 *
 * @author NeuroVault Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepairResultDto {

    /** Number of chunks inspected during the repair scan. */
    private int chunksInspected;

    /** Number of repair operations initiated. */
    private int repairsInitiated;

    /** Number of successful repairs. */
    private int repairsSucceeded;

    /** Number of repair operations that failed. */
    private int repairsFailed;

    /** Detailed descriptions of each repair action. */
    @Builder.Default
    private List<String> details = List.of();

    /** Timestamp when the repair was triggered. */
    private LocalDateTime timestamp;
}
