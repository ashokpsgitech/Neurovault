package com.neurovault.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing a periodic health heartbeat report from a host.
 */
@Entity
@Table(name = "host_heartbeats")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostHeartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", nullable = false)
    private Host host;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "cpu_usage_percent")
    private Double cpuUsagePercent;

    @Column(name = "memory_usage_percent")
    private Double memoryUsagePercent;

    @Column(name = "storage_used_bytes")
    private Long storageUsedBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Host.Status status;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}
