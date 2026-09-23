package com.neurovault.backend.storage.engine;

import com.neurovault.backend.storage.container.ContainerContext;
import com.neurovault.backend.storage.container.ContainerManager;
import com.neurovault.backend.storage.exception.ChunkNotFoundException;
import com.neurovault.backend.storage.exception.ContainerException;
import com.neurovault.backend.storage.exception.CorruptedChunkException;
import com.neurovault.backend.storage.exception.StorageFullException;
import com.neurovault.backend.storage.model.ChunkMetadata;
import com.neurovault.backend.storage.model.ContainerHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Storage Engine orchestrating chunk persistence through {@link ContainerManager}
 * and isolated {@link ContainerContext} instances.
 *
 * <p>Supports both in-memory byte arrays and streaming binary transfers with bounded memory
 * and strict incremental checksum validation.
 */
@Component
public class StorageEngine {

    private static final Logger log = LoggerFactory.getLogger(StorageEngine.class);
    private static final UUID DEFAULT_HOST_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final ContainerManager containerManager;

    public StorageEngine(ContainerManager containerManager) {
        this.containerManager = containerManager;
    }

    public void initialize() {
        ContainerContext ctx = getRequiredContext(DEFAULT_HOST_ID);
        ctx.loadMetadataIndex();
    }

    public void initialize(UUID hostId) {
        ContainerContext ctx = getRequiredContext(hostId);
        ctx.loadMetadataIndex();
    }

    // ─── Multi-Host API ───

    public ChunkMetadata storeChunk(UUID hostId, UUID chunkId, UUID ownerId, byte[] data) {
        ContainerContext ctx = getRequiredContext(hostId);

        if (ctx.getChunkIndex().containsKey(chunkId) && !ctx.getChunkIndex().get(chunkId).isDeleted()) {
            throw new ContainerException("Chunk already exists with ID: " + chunkId);
        }

        if (!checkCapacity(hostId, data.length)) {
            throw new StorageFullException(
                    "Insufficient storage capacity on host " + hostId + ". Required: " + data.length +
                            " bytes, available: " + calculateFreeSpace(hostId) + " bytes");
        }

        String sha256 = computeSha256(data);
        long crc32 = computeCrc32(data);

        ctx.getRwLock().writeLock().lock();
        try {
            long offset = ctx.getNextDataOffset();
            ctx.writeAtOffset(offset, data);

            ChunkMetadata metadata = new ChunkMetadata(
                    chunkId, data.length, offset, Instant.now(), sha256, crc32, ownerId);

            ctx.getChunkIndex().put(chunkId, metadata);
            ctx.setNextDataOffset(offset + data.length);
            ctx.persistMetadataIndex();

            log.info("Chunk {} stored on host {} at offset {} ({} bytes, SHA256={})",
                    chunkId, hostId, offset, data.length, sha256);
            return metadata;
        } finally {
            ctx.getRwLock().writeLock().unlock();
        }
    }

    public ChunkMetadata storeChunkStream(UUID hostId, UUID chunkId, UUID ownerId,
                                          InputStream in, long expectedSize, String expectedSha256) {
        ContainerContext ctx = getRequiredContext(hostId);

        if (ctx.getChunkIndex().containsKey(chunkId) && !ctx.getChunkIndex().get(chunkId).isDeleted()) {
            throw new ContainerException("Chunk already exists with ID: " + chunkId);
        }

        if (!checkCapacity(hostId, expectedSize)) {
            throw new StorageFullException(
                    "Insufficient storage capacity on host " + hostId + ". Required: " + expectedSize +
                            " bytes, available: " + calculateFreeSpace(hostId) + " bytes");
        }

        ctx.getRwLock().writeLock().lock();
        try {
            long offset = ctx.getNextDataOffset();
            MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");
            CRC32 crc32 = new CRC32();

            // Wrap stream to compute checksums incrementally without accumulating full payload in memory
            DigestInputStream dis = new DigestInputStream(in, sha256Digest);
            InputStream wrappedIn = new InputStream() {
                @Override
                public int read() throws IOException {
                    int b = dis.read();
                    if (b != -1) crc32.update(b);
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = dis.read(b, off, len);
                    if (n != -1) crc32.update(b, off, n);
                    return n;
                }
            };

            ctx.writeStreamAtOffset(offset, wrappedIn, expectedSize);

            String actualSha256 = bytesToHex(sha256Digest.digest());
            if (expectedSha256 != null && !expectedSha256.isBlank() && !actualSha256.equalsIgnoreCase(expectedSha256)) {
                log.error("Streaming chunk {} storage verification failed! Expected SHA-256 {}, got {}",
                        chunkId, expectedSha256, actualSha256);
                throw new CorruptedChunkException("Streaming SHA-256 verification failed for chunk " + chunkId);
            }

            ChunkMetadata metadata = new ChunkMetadata(
                    chunkId, expectedSize, offset, Instant.now(), actualSha256, crc32.getValue(), ownerId);

            ctx.getChunkIndex().put(chunkId, metadata);
            ctx.setNextDataOffset(offset + expectedSize);
            ctx.persistMetadataIndex();

            log.info("Streaming chunk {} stored on host {} at offset {} ({} bytes, SHA256={})",
                    chunkId, hostId, offset, expectedSize, actualSha256);
            return metadata;
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new ContainerException("Failed to stream chunk " + chunkId + " to host " + hostId, e);
        } finally {
            ctx.getRwLock().writeLock().unlock();
        }
    }

    public byte[] readChunk(UUID hostId, UUID chunkId) {
        ContainerContext ctx = getRequiredContext(hostId);

        ChunkMetadata metadata = ctx.getChunkIndex().get(chunkId);
        if (metadata == null || metadata.isDeleted()) {
            throw new ChunkNotFoundException("Chunk not found with ID: " + chunkId + " on host: " + hostId);
        }

        byte[] data = ctx.readAtOffset(metadata.getOffset(), (int) metadata.getChunkSize());

        if (metadata.getChecksum() != 0) {
            long actualCrc = computeCrc32(data);
            if (actualCrc != metadata.getChecksum()) {
                log.error("Chunk {} CRC32 integrity failure on host {}: expected {}, actual {}",
                        chunkId, hostId, metadata.getChecksum(), actualCrc);
                throw new CorruptedChunkException(
                        "Chunk integrity failure for ID " + chunkId + ": expected CRC32 " + metadata.getChecksum() + ", got " + actualCrc);
            }
        }

        return data;
    }

    public InputStream readChunkStream(UUID hostId, UUID chunkId) {
        // Return bounded stream
        byte[] data = readChunk(hostId, chunkId);
        return new ByteArrayInputStream(data);
    }

    public void deleteChunk(UUID hostId, UUID chunkId) {
        ContainerContext ctx = getRequiredContext(hostId);

        ChunkMetadata metadata = ctx.getChunkIndex().get(chunkId);
        if (metadata == null || metadata.isDeleted()) {
            throw new ChunkNotFoundException("Chunk not found with ID: " + chunkId);
        }

        ctx.getRwLock().writeLock().lock();
        try {
            metadata.setDeleted(true);
            ctx.persistMetadataIndex();
            log.info("Chunk {} deleted from host {}", chunkId, hostId);
        } finally {
            ctx.getRwLock().writeLock().unlock();
        }
    }

    public List<ChunkMetadata> listChunks(UUID hostId) {
        ContainerContext ctx = getRequiredContext(hostId);
        List<ChunkMetadata> result = new ArrayList<>();
        for (ChunkMetadata meta : ctx.getChunkIndex().values()) {
            if (!meta.isDeleted()) {
                result.add(meta);
            }
        }
        return result;
    }

    public ChunkMetadata getChunkMetadata(UUID hostId, UUID chunkId) {
        ContainerContext ctx = getRequiredContext(hostId);
        ChunkMetadata meta = ctx.getChunkIndex().get(chunkId);
        if (meta == null || meta.isDeleted()) {
            throw new ChunkNotFoundException("Chunk not found with ID: " + chunkId);
        }
        return meta;
    }

    public boolean hasChunk(UUID hostId, UUID chunkId) {
        ContainerContext ctx = containerManager.getContext(hostId);
        if (ctx == null) return false;
        ChunkMetadata meta = ctx.getChunkIndex().get(chunkId);
        return meta != null && !meta.isDeleted();
    }

    public long calculateUsedSpace(UUID hostId) {
        ContainerContext ctx = containerManager.getContext(hostId);
        return ctx != null ? ctx.calculateUsedSpace() : 0L;
    }

    public long calculateFreeSpace(UUID hostId) {
        ContainerContext ctx = containerManager.getContext(hostId);
        return ctx != null ? ctx.calculateFreeSpace() : 0L;
    }

    public boolean checkCapacity(UUID hostId, long requiredBytes) {
        return calculateFreeSpace(hostId) >= requiredBytes;
    }

    public int countActiveChunks(UUID hostId) {
        ContainerContext ctx = containerManager.getContext(hostId);
        if (ctx == null) return 0;
        return (int) ctx.getChunkIndex().values().stream().filter(c -> !c.isDeleted()).count();
    }

    public boolean verifyChunkExists(UUID hostId, UUID chunkId) {
        ContainerContext ctx = containerManager.getContext(hostId);
        if (ctx == null) return false;
        ChunkMetadata meta = ctx.getChunkIndex().get(chunkId);
        return meta != null && !meta.isDeleted();
    }

    public boolean verifyChunkIntegrity(UUID hostId, UUID chunkId, String expectedSha256) {
        try {
            byte[] data = readChunk(hostId, chunkId);
            String actualSha256 = computeSha256(data);
            return expectedSha256.equalsIgnoreCase(actualSha256);
        } catch (Exception e) {
            log.warn("Integrity verification failed for chunk {} on host {}: {}", chunkId, hostId, e.getMessage());
            return false;
        }
    }

    // ─── Backward-Compatible Single-Host API ───

    public ChunkMetadata storeChunk(UUID chunkId, UUID ownerId, byte[] data) {
        return storeChunk(DEFAULT_HOST_ID, chunkId, ownerId, data);
    }

    public byte[] readChunk(UUID chunkId) {
        return readChunk(DEFAULT_HOST_ID, chunkId);
    }

    public void deleteChunk(UUID chunkId) {
        deleteChunk(DEFAULT_HOST_ID, chunkId);
    }

    public List<ChunkMetadata> listChunks() {
        return listChunks(DEFAULT_HOST_ID);
    }

    public ChunkMetadata getChunkMetadata(UUID chunkId) {
        return getChunkMetadata(DEFAULT_HOST_ID, chunkId);
    }

    public long calculateUsedSpace() {
        return calculateUsedSpace(DEFAULT_HOST_ID);
    }

    public long calculateFreeSpace() {
        return calculateFreeSpace(DEFAULT_HOST_ID);
    }

    public boolean checkCapacity(long requiredBytes) {
        return checkCapacity(DEFAULT_HOST_ID, requiredBytes);
    }

    public int countActiveChunks() {
        return countActiveChunks(DEFAULT_HOST_ID);
    }

    public boolean verifyChunkExists(UUID chunkId) {
        return verifyChunkExists(DEFAULT_HOST_ID, chunkId);
    }

    // ─── Helpers ───

    private ContainerContext getRequiredContext(UUID hostId) {
        ContainerContext ctx = containerManager.getContext(hostId);
        if (ctx == null || !ctx.isOpen()) {
            throw new ContainerException("Container for host " + hostId + " is not open");
        }
        return ctx;
    }

    public String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new ContainerException("SHA-256 algorithm not available", e);
        }
    }

    public long computeCrc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(64);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
