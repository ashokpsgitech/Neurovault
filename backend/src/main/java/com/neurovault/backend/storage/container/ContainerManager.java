package com.neurovault.backend.storage.container;

import com.neurovault.backend.storage.exception.ContainerException;
import com.neurovault.backend.storage.model.ContainerHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;

/**
 * Core binary container file manager using Java NIO.
 *
 * <p>The container is a single binary file ({@code storage.container}) that contains
 * all encrypted chunks for a host. The OS sees only one file — individual client
 * files are never directly visible.
 *
 * <h3>Container Binary Layout</h3>
 * <pre>
 * [Header: 256B] [Metadata Index: variable] [Data Region: remaining]
 * </pre>
 *
 * <p>This class provides low-level I/O operations: create, open, close, delete,
 * resize, read/write at offset, integrity verification, and file locking.
 *
 * <p>Thread safety: All I/O operations are synchronized on this instance.
 * For distributed safety, use {@link #acquireLock()} / {@link #releaseLock()}.
 */
@Component
public class ContainerManager {

    private static final Logger log = LoggerFactory.getLogger(ContainerManager.class);

    private FileChannel fileChannel;
    private ContainerHeader header;
    private Path containerPath;
    private FileLock fileLock;
    private boolean open;

    /**
     * Creates a new container file at the specified path with the given total size.
     * Writes the header and pre-allocates the file to the requested size.
     *
     * @param path      the file path for the container
     * @param totalSize the total container size in bytes
     * @throws ContainerException if the container already exists or I/O fails
     */
    public synchronized void createContainer(Path path, long totalSize) {
        log.info("Creating container at {} with size {} bytes", path, totalSize);

        if (Files.exists(path)) {
            throw new ContainerException("Container already exists at: " + path);
        }

        try {
            // Ensure parent directories exist
            Files.createDirectories(path.getParent());

            fileChannel = FileChannel.open(path,
                    EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE));

            // Build the header
            header = new ContainerHeader();
            header.setTotalSize(totalSize);
            header.setUsedSize(0);
            header.setChunkCount(0);
            header.setMetadataRegionOffset(ContainerHeader.HEADER_SIZE);
            header.setMetadataRegionSize(ContainerHeader.DEFAULT_METADATA_REGION_SIZE);
            header.setDataRegionOffset(ContainerHeader.HEADER_SIZE + ContainerHeader.DEFAULT_METADATA_REGION_SIZE);

            // Write the header
            writeHeader();

            // Pre-allocate the file to the total size by writing a zero byte at the end
            fileChannel.position(totalSize - 1);
            fileChannel.write(ByteBuffer.wrap(new byte[]{0}));
            fileChannel.force(true);

            this.containerPath = path;
            this.open = true;

            log.info("Container created successfully: {} ({} bytes)", path, totalSize);

        } catch (IOException e) {
            close();
            throw new ContainerException("Failed to create container at: " + path, e);
        }
    }

    /**
     * Opens an existing container file, reads and validates the header.
     *
     * @param path the file path of the container
     * @throws ContainerException if the file doesn't exist, is invalid, or I/O fails
     */
    public synchronized void openContainer(Path path) {
        log.info("Opening container at {}", path);

        if (!Files.exists(path)) {
            throw new ContainerException("Container does not exist at: " + path);
        }

        try {
            fileChannel = FileChannel.open(path,
                    EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE));

            // Read and validate the header
            header = readHeaderFromChannel();

            this.containerPath = path;
            this.open = true;

            log.info("Container opened: {} (size={}, chunks={}, used={})",
                    path, header.getTotalSize(), header.getChunkCount(), header.getUsedSize());

        } catch (IOException e) {
            close();
            throw new ContainerException("Failed to open container at: " + path, e);
        }
    }

    /**
     * Closes the container file channel and releases any held lock.
     */
    public synchronized void closeContainer() {
        log.info("Closing container");
        close();
    }

    /**
     * Deletes the container file from disk.
     * Closes the file channel first if it's open.
     *
     * @param path the file path of the container to delete
     * @throws ContainerException if the file cannot be deleted
     */
    public synchronized void deleteContainer(Path path) {
        log.info("Deleting container at {}", path);

        // Close if this is the currently open container
        if (open && containerPath != null && containerPath.equals(path)) {
            close();
        }

        try {
            if (Files.exists(path)) {
                Files.delete(path);
                log.info("Container deleted: {}", path);
            } else {
                log.warn("Container file does not exist, nothing to delete: {}", path);
            }
        } catch (IOException e) {
            throw new ContainerException("Failed to delete container at: " + path, e);
        }
    }

    /**
     * Resizes the container to a new total size.
     * Only expansion is supported (new size must be >= current total size).
     *
     * @param newTotalSize the new total size in bytes
     * @throws ContainerException if not open, size is smaller, or I/O fails
     */
    public synchronized void resizeContainer(long newTotalSize) {
        ensureOpen();

        if (newTotalSize < header.getTotalSize()) {
            throw new ContainerException(
                    "Cannot shrink container. Current: " + header.getTotalSize() + ", requested: " + newTotalSize);
        }

        if (newTotalSize == header.getTotalSize()) {
            log.info("Container already at requested size: {} bytes", newTotalSize);
            return;
        }

        log.info("Resizing container from {} to {} bytes", header.getTotalSize(), newTotalSize);

        try {
            // Extend the file by writing a zero byte at the new end
            fileChannel.position(newTotalSize - 1);
            fileChannel.write(ByteBuffer.wrap(new byte[]{0}));

            header.setTotalSize(newTotalSize);
            header.setLastModifiedAt(java.time.Instant.now());
            writeHeader();

            fileChannel.force(true);

            log.info("Container resized to {} bytes", newTotalSize);

        } catch (IOException e) {
            throw new ContainerException("Failed to resize container", e);
        }
    }

    /**
     * Verifies container integrity by validating the header magic bytes
     * and checking that the file size matches the declared total size.
     *
     * @return {@code true} if the container passes integrity checks
     * @throws ContainerException if the container is not open
     */
    public synchronized boolean verifyIntegrity() {
        ensureOpen();

        try {
            ContainerHeader diskHeader = readHeaderFromChannel();

            // Verify magic bytes (already done in fromBytes, but explicit check)
            long fileSize = fileChannel.size();
            boolean sizeMatch = fileSize >= diskHeader.getTotalSize();

            if (!sizeMatch) {
                log.error("Container integrity check failed: file size {} < declared size {}",
                        fileSize, diskHeader.getTotalSize());
            }

            return sizeMatch;

        } catch (IOException e) {
            throw new ContainerException("Failed to verify container integrity", e);
        } catch (IllegalArgumentException e) {
            log.error("Container integrity check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Reads {@code length} bytes from the given offset in the container.
     *
     * @param offset the byte offset to read from
     * @param length the number of bytes to read
     * @return the bytes read
     * @throws ContainerException if the container is not open or I/O fails
     */
    public synchronized byte[] readAtOffset(long offset, int length) {
        ensureOpen();

        try {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            int bytesRead = fileChannel.read(buffer, offset);

            if (bytesRead < length) {
                log.warn("Read only {} of {} bytes at offset {}", bytesRead, length, offset);
            }

            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            return data;

        } catch (IOException e) {
            throw new ContainerException("Failed to read " + length + " bytes at offset " + offset, e);
        }
    }

    /**
     * Writes data at the given offset in the container.
     *
     * @param offset the byte offset to write to
     * @param data   the bytes to write
     * @throws ContainerException if the container is not open or I/O fails
     */
    public synchronized void writeAtOffset(long offset, byte[] data) {
        ensureOpen();

        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            fileChannel.write(buffer, offset);

        } catch (IOException e) {
            throw new ContainerException("Failed to write " + data.length + " bytes at offset " + offset, e);
        }
    }

    /**
     * Returns the current container header (in-memory copy).
     *
     * @return the container header, or {@code null} if not open
     */
    public synchronized ContainerHeader getHeader() {
        return header;
    }

    /**
     * Updates the container header on disk with the current in-memory state.
     *
     * @throws ContainerException if the container is not open or I/O fails
     */
    public synchronized void flushHeader() {
        ensureOpen();
        writeHeader();
    }

    /**
     * Acquires an exclusive file lock on the container.
     * This provides OS-level protection against concurrent access from multiple processes.
     *
     * @throws ContainerException if the lock cannot be acquired
     */
    public synchronized void acquireLock() {
        ensureOpen();

        try {
            if (fileLock == null || !fileLock.isValid()) {
                fileLock = fileChannel.tryLock();
                if (fileLock == null) {
                    throw new ContainerException("Cannot acquire lock: container is locked by another process");
                }
                log.debug("Container lock acquired");
            }
        } catch (IOException e) {
            throw new ContainerException("Failed to acquire container lock", e);
        }
    }

    /**
     * Releases the file lock on the container.
     */
    public synchronized void releaseLock() {
        if (fileLock != null && fileLock.isValid()) {
            try {
                fileLock.release();
                fileLock = null;
                log.debug("Container lock released");
            } catch (IOException e) {
                log.error("Failed to release container lock", e);
            }
        }
    }

    /**
     * Forces any system buffers to be written to disk.
     *
     * @throws ContainerException if I/O fails
     */
    public synchronized void sync() {
        ensureOpen();
        try {
            fileChannel.force(true);
        } catch (IOException e) {
            throw new ContainerException("Failed to sync container to disk", e);
        }
    }

    /**
     * Returns whether the container is currently open.
     */
    public synchronized boolean isOpen() {
        return open && fileChannel != null && fileChannel.isOpen();
    }

    /**
     * Returns the path of the currently open container.
     */
    public synchronized Path getContainerPath() {
        return containerPath;
    }

    // ---- Private helpers ----

    private void ensureOpen() {
        if (!open || fileChannel == null || !fileChannel.isOpen()) {
            throw new ContainerException("Container is not open");
        }
    }

    private void writeHeader() {
        try {
            byte[] headerBytes = header.toBytes();
            fileChannel.write(ByteBuffer.wrap(headerBytes), 0);
        } catch (IOException e) {
            throw new ContainerException("Failed to write container header", e);
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

    private void close() {
        releaseLock();

        if (fileChannel != null) {
            try {
                fileChannel.close();
            } catch (IOException e) {
                log.error("Error closing container file channel", e);
            }
        }

        fileChannel = null;
        header = null;
        containerPath = null;
        open = false;
    }
}
