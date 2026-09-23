package com.neurovault.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

/**
 * Entity representing a single encrypted file chunk.
 */
@Entity
@Table(name = "chunks", uniqueConstraints = {
    @UniqueConstraint(name = "uk_chunk_file_index", columnNames = {"file_id", "chunk_index"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private FileMetadata file;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    public enum Status {
        PENDING,
        ACTIVE,
        MISSING,
        CORRUPTED
    }
}
