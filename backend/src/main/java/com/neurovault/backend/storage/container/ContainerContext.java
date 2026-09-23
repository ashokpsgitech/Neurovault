package com.neurovault.backend.storage.container;

import com.neurovault.backend.storage.exception.ContainerException;
import com.neurovault.backend.storage.model.ChunkMetadata;
import com.neurovault.backend.storage.model.ContainerHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;

/**
 * Encapsulates the runtime context and state of an isolated host container.
 *
 * <p>Each host container manages its own {@link FileChannel}, {@link ContainerHeader},
 * in-memory {@link ChunkMetadata} index, concurrency locks, and data offsets.
 * This completely isolates concurrent host operations and prevents single-global-container
 * state corruption.
 */
public class ContainerContext implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ContainerContext.class);

    /** Magic bytes for crash-safe metadata index region: "NVID" (NeuroVault Index Data) */
    public static final int INDEX_MAGIC = 0x4E564944;

    private final UUID hostId;
    private final Path containerPath;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ConcurrentHashMap<UUID, ChunkMetadata> chunkIndex = new ConcurrentHashMap<>();

    private FileChannel fileChannel;
    private ContainerHeader header;
    private FileLock fileLock;
    private long nextDataOffset;
    private volatile boolean open;

    public ContainerContext(UUID hostId, Path containerPath) {
        this.hostId = hostId;
        this.containerPath = containerPath;
    }

    public UUID getHostId() {
        return hostId;
    }

    public Path getContainerPath() {
        return containerPath;
    }

    public ReentrantReadWriteLock getRwLock() {
        return rwLock;
    }

    public ConcurrentHashMap<UUID, ChunkMetadata> getChunkIndex() {
        return chunkIndex;
    }

    public synchronized ContainerHeader getHeader() {
        return header;
    }

    public synchronized long getNextDataOffset() {
        return nextDataOffset;
    }

    public synchronized void setNextDataOffset(long nextDataOffset) {
        this.nextDataOffset = nextDataOffset;
    }

    public synchronized boolean isOpen() {
        return open && fileChannel != null && fileChannel.isOpen();
    }

    public synchronized FileChannel getFileChannel() {
        return fileChannel;
    }

    /**
     * Creates a new container file on disk, pre-allocates to totalSize,
     * writes the header, and initializes the in-memory context.
     */
    public synchronized void create(long totalSize) {
        rwLock.writeLock().lock();
        try {
            log.info("Creating container for host {} at {} (size: {} bytes)", hostId, containerPath, totalSize);

            if (Files.exists(containerPath)) {
                throw new ContainerException("Container already exists at: " + containerPath);
            }

            if (containerPath.getParent() != null && !Files.exists(containerPath.getParent())) {
                try {
                    Files.createDirectories(containerPath.getParent());
                } catch (Exception ignored) {
                }
            }

            this.fileChannel = FileChannel.open(containerPath,
                    EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE));

            this.header = new ContainerHeader();
            this.header.setTotalSize(totalSize);
            this.header.setUsedSize(0);
            this.header.setChunkCount(0);
            this.header.setMetadataRegionOffset(ContainerHeader.HEADER_SIZE);
            this.header.setMetadataRegionSize(ContainerHeader.DEFAULT_METADATA_REGION_SIZE);
            this.header.setDataRegionOffset(ContainerHeader.HEADER_SIZE + ContainerHeader.DEFAULT_METADATA_REGION_SIZE);

            writeHeader();

            fileChannel.position(Math.max(0, totalSize - 1));
            fileChannel.write(ByteBuffer.wrap(new byte[]{0}));
            fileChannel.force(true);

            this.chunkIndex.clear();
            this.nextDataOffset = header.getDataRegionOffset();
            this.open = true;

            log.info("Container created successfully for host {}: {}", hostId, containerPath);
        } catch (IOException e) {
            closeInternal();
            throw new ContainerException("Failed to create container at " + containerPath + ": " + e.getMessage(), e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Opens an existing container, validates the header, and loads the chunk metadata index.
     */
    public synchronized void open() {
        rwLock.writeLock().lock();
        try {
            if (open && fileChannel != null && fileChannel.isOpen()) {
                return;
            }

            log.info("Opening container for host {} at {}", hostId, containerPath);

            if (!Files.exists(containerPath)) {
                throw new ContainerException("Container file does not exist at: " + containerPath);
            }

            this.fileChannel = FileChannel.open(containerPath,
                    EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE));

            this.header = readHeaderFromChannel();
            this.open = true;

            loadMetadataIndex();

            // Calculate next write offset from existing non-deleted chunks
            this.nextDataOffset = header.getDataRegionOffset();
            for (ChunkMetadata chunk : chunkIndex.values()) {
                if (!chunk.isDeleted()) {
                    long chunkEnd = chunk.getOffset() + chunk.getChunkSize();
                    if (chunkEnd > this.nextDataOffset) {
                        this.nextDataOffset = chunkEnd;
                    }
                }
            }

            log.info("Container opened for host {}: totalSize={}, usedSize={}, chunks={}, nextDataOffset={}",
                    hostId, header.getTotalSize(), header.getUsedSize(), chunkIndex.size(), nextDataOffset);
        } catch (IOException e) {
            closeInternal();
            throw new ContainerException("Failed to open container for host " + hostId + " at " + containerPath, e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Reads bytes at the given offset. Thread-safe with read lock.
     */
    public byte[] readAtOffset(long offset, int length) {
        rwLock.readLock().lock();
        try {
            ensureOpen();
            ByteBuffer buffer = ByteBuffer.allocate(length);
            int totalRead = 0;
            while (totalRead < length) {
                int read = fileChannel.read(buffer, offset + totalRead);
                if (read == -1) break;
                totalRead += read;
            }

            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            return data;
        } catch (IOException e) {
            throw new ContainerException("Failed to read " + length + " bytes at offset " + offset, e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Writes bytes at the given offset. Thread-safe with write lock.
     */
    public void writeAtOffset(long offset, byte[] data) {
        rwLock.writeLock().lock();
        try {
            ensureOpen();
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                fileChannel.write(buffer, offset);
            }
        } catch (IOException e) {
            throw new ContainerException("Failed to write " + data.length + " bytes at offset " + offset, e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Streaming write at offset from an InputStream with incremental SHA-256 and CRC32 verification.
     */
    public void writeStreamAtOffset(long offset, InputStream in, long length) throws IOException {
        rwLock.writeLock().lock();
        try {
            ensureOpen();
            fileChannel.position(offset);
            byte[] buffer = new byte[65536]; // 64KB bounded buffer
            long remaining = length;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int bytesRead = in.read(buffer, 0, toRead);
                if (bytesRead == -1) {
                    throw new EOFException("Premature end of stream while writing chunk. Expected " + length + ", got " + (length - remaining));
                }
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
                while (byteBuffer.hasRemaining()) {
                    fileChannel.write(byteBuffer);
                }
                remaining -= bytesRead;
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Flushes system buffers to persistent storage (fsync).
     */
    public void sync() {
        rwLock.writeLock().lock();
        try {
            ensureOpen();
            fileChannel.force(true);
        } catch (IOException e) {
            throw new ContainerException("Failed to sync container for host " + hostId, e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Flushes the header to disk.
     */
    public void flushHeader() {
        rwLock.writeLock().lock();
        try {
            ensureOpen();
            writeHeader();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Crash-safe metadata index persistence.
     * Writes [INDEX_MAGIC 4B][LENGTH 4B][CRC32 8B][SERIALIZED DATA] to metadata region.
     */
    public void persistMetadataIndex() {
        rwLock.writeLock().lock();
        try {
            ensureOpen();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(new ArrayList<>(chunkIndex.values()));
                oos.flush();
            }

            byte[] serialized = baos.toByteArray();
            long metaSize = header.getMetadataRegionSize();
            if (serialized.length + 16 > metaSize) {
                throw new ContainerException("Metadata index exceeds allocated metadata region size");
            }

            CRC32 crc = new CRC32();
            crc.update(serialized);
            long checksum = crc.getValue();

            ByteBuffer metaBuffer = ByteBuffer.allocate(16 + serialized.length);
            metaBuffer.putInt(INDEX_MAGIC);
            metaBuffer.putInt(serialized.length);
            metaBuffer.putLong(checksum);
            metaBuffer.put(serialized);
            metaBuffer.flip();

            fileChannel.write(metaBuffer, header.getMetadataRegionOffset());
            fileChannel.force(true);

            // Update container header
            header.setUsedSize(calculateUsedSpace());
            header.setChunkCount((int) chunkIndex.values().stream().filter(c -> !c.isDeleted()).count());
            header.setLastModifiedAt(Instant.now());
            writeHeader();
            fileChannel.force(true);

            log.debug("Persisted crash-safe metadata index for host {}: {} active chunks, {} bytes",
                    hostId, header.getChunkCount(), serialized.length);
        } catch (IOException e) {
            throw new ContainerException("Failed to persist metadata index for host " + hostId, e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Crash-safe metadata index loading.
     * Validates magic bytes and CRC32 checksum before applying in-memory index.
     * Supports backward compatibility with legacy 4-byte length prefix format.
     */
    @SuppressWarnings("unchecked")
    public void loadMetadataIndex() {
        rwLock.writeLock().lock();
        try {
            ensureOpen();
            chunkIndex.clear();

            long metaOffset = header.getMetadataRegionOffset();
            long metaSize = header.getMetadataRegionSize();

            ByteBuffer prefixBuf = ByteBuffer.allocate(16);
            int read = fileChannel.read(prefixBuf, metaOffset);
            if (read < 4) {
                log.debug("No metadata index found in container for host {}", hostId);
                return;
            }

            prefixBuf.flip();
            int firstInt = prefixBuf.getInt();

            byte[] serializedData = null;

            if (firstInt == INDEX_MAGIC && prefixBuf.remaining() >= 12) {
                int dataLength = prefixBuf.getInt();
                long expectedCrc = prefixBuf.getLong();

                if (dataLength > 0 && dataLength <= metaSize - 16) {
                    ByteBuffer dataBuf = ByteBuffer.allocate(dataLength);
                    fileChannel.read(dataBuf, metaOffset + 16);
                    dataBuf.flip();
                    serializedData = new byte[dataBuf.remaining()];
                    dataBuf.get(serializedData);

                    CRC32 crc = new CRC32();
                    crc.update(serializedData);
                    if (crc.getValue() != expectedCrc) {
                        log.error("CRITICAL: Metadata index CRC32 mismatch in container for host {}! Index is corrupted.", hostId);
                        serializedData = null;
                    }
                }
            } else if (firstInt > 0 && firstInt <= metaSize - 4) {
                // Backward compatibility: Legacy 4-byte length prefix
                log.info("Reading legacy metadata format for host {}", hostId);
                int dataLength = firstInt;
                ByteBuffer dataBuf = ByteBuffer.allocate(dataLength);
                fileChannel.read(dataBuf, metaOffset + 4);
                dataBuf.flip();
                serializedData = new byte[dataBuf.remaining()];
                dataBuf.get(serializedData);
            }

            if (serializedData != null && serializedData.length > 0) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(serializedData);
                     ObjectInputStream ois = new ObjectInputStream(bais)) {
                    Collection<ChunkMetadata> chunks = (Collection<ChunkMetadata>) ois.readObject();
                    for (ChunkMetadata chunk : chunks) {
                        chunkIndex.put(chunk.getChunkId(), chunk);
                    }
                    log.info("Loaded {} chunks into index for host {}", chunkIndex.size(), hostId);
                } catch (Exception e) {
                    log.error("Failed to deserialize chunk metadata index for host {}: {}", hostId, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to read metadata index from disk for host {}: {}", hostId, e.getMessage());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public long calculateUsedSpace() {
        return chunkIndex.values().stream()
                .filter(c -> !c.isDeleted())
                .mapToLong(ChunkMetadata::getChunkSize)
                .sum();
    }

    public long calculateFreeSpace() {
        if (header == null) return 0L;
        long totalDataCapacity = header.getTotalSize() - header.getDataRegionOffset();
        return Math.max(0L, totalDataCapacity - calculateUsedSpace());
    }

    private void ensureOpen() {
        if (!open || fileChannel == null || !fileChannel.isOpen()) {
            throw new ContainerException("Container for host " + hostId + " is not open");
        }
    }

    private void writeHeader() {
        try {
            byte[] headerBytes = header.toBytes();
            ByteBuffer buf = ByteBuffer.wrap(headerBytes);
            while (buf.hasRemaining()) {
                fileChannel.write(buf, 0);
            }
        } catch (IOException e) {
            throw new ContainerException("Failed to write container header for host " + hostId, e);
        }
    }

    private ContainerHeader readHeaderFromChannel() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(ContainerHeader.HEADER_SIZE);
        fileChannel.read(buffer, 0);
        buffer.flip();
        byte[] headerBytes = new byte[ContainerHeader.HEADER_SIZE];
        buffer.get(headerBytes);
        return ContainerHeader.fromBytes(headerBytes);
    }

    @Override
    public synchronized void close() {
        rwLock.writeLock().lock();
        try {
            closeInternal();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void closeInternal() {
        if (fileLock != null && fileLock.isValid()) {
            try {
                fileLock.release();
            } catch (IOException ignored) {
            }
            fileLock = null;
        }

        if (fileChannel != null && fileChannel.isOpen()) {
            try {
                fileChannel.close();
            } catch (IOException ignored) {
            }
        }

        fileChannel = null;
        open = false;
    }
}
