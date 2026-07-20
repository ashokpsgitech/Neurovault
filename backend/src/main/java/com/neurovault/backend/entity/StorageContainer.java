package com.neurovault.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing a reserved storage container file structure on a host.
 */
@Entity
@Table(name = "storage_containers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageContainer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", nullable = false, unique = true)
    private Host host;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "total_size", nullable = false)
    private Long totalSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum Status {
        ACTIVE,
        CORRUPTED,
        INACTIVE
    }
}
