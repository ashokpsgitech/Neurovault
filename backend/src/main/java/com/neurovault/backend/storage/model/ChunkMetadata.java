package com.neurovault.backend.storage.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata record for a single encrypted chunk stored inside the binary container.
 * This POJO is serialized into the container's metadata index region and also
 * kept in an in-memory index for fast lookups.
 *
 * <p>No decryption occurs at this level — only raw encrypted bytes are tracked.
 */
public class ChunkMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID chunkId;
    private long chunkSize;
    private long offset;
    private Instant creationTime;
    private String sha256Hash;
    private long checksum; // CRC32
    private UUID ownerId;
    private boolean deleted;

    public ChunkMetadata() {
    }

    public ChunkMetadata(UUID chunkId, long chunkSize, long offset, Instant creationTime,
                         String sha256Hash, long checksum, UUID ownerId) {
        this.chunkId = chunkId;
        this.chunkSize = chunkSize;
        this.offset = offset;
        this.creationTime = creationTime;
        this.sha256Hash = sha256Hash;
        this.checksum = checksum;
        this.ownerId = ownerId;
        this.deleted = false;
    }

    public UUID getChunkId() {
        return chunkId;
    }

    public void setChunkId(UUID chunkId) {
        this.chunkId = chunkId;
    }

    public long getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(long chunkSize) {
        this.chunkSize = chunkSize;
    }

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Instant creationTime) {
        this.creationTime = creationTime;
    }

    public String getSha256Hash() {
        return sha256Hash;
    }

    public void setSha256Hash(String sha256Hash) {
        this.sha256Hash = sha256Hash;
    }

    public long getChecksum() {
        return checksum;
    }

    public void setChecksum(long checksum) {
        this.checksum = checksum;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    @Override
    public String toString() {
        return "ChunkMetadata{" +
                "chunkId=" + chunkId +
                ", chunkSize=" + chunkSize +
                ", offset=" + offset +
                ", sha256Hash='" + sha256Hash + '\'' +
                ", ownerId=" + ownerId +
                ", deleted=" + deleted +
                '}';
    }
}
