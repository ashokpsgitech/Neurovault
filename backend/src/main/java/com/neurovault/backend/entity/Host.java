package com.neurovault.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing a storage host device (micro-server).
 */
@Entity
@Table(name = "hosts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Host {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Column(name = "operating_system", length = 50)
    private String operatingSystem;

    @Column(name = "public_ip", length = 45)
    private String publicIp;

    @Column(name = "total_capacity_bytes", nullable = false)
    private Long totalCapacityBytes;

    @Column(name = "reserved_capacity_bytes", nullable = false)
    private Long reservedCapacityBytes;

    @Column(name = "used_capacity_bytes", nullable = false)
    private Long usedCapacityBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Mode mode = Mode.PRIVATE;

    @Column(name = "is_anonymous")
    @Builder.Default
    private Boolean isAnonymous = false;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "heartbeat_interval_seconds", nullable = false)
    @Builder.Default
    private Integer heartbeatIntervalSeconds = 30;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (heartbeatIntervalSeconds == null) {
            heartbeatIntervalSeconds = 30;
        }
        if (mode == null) mode = Mode.PRIVATE;
        if (isAnonymous == null) isAnonymous = false;
    }

    public enum Status {
        ONLINE,
        OFFLINE
    }

    public enum Mode {
        PRIVATE,
        PUBLIC
    }
}
