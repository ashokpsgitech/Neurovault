package com.neurovault.backend.storage.container;

import com.neurovault.backend.storage.exception.ContainerException;
import com.neurovault.backend.storage.model.ContainerHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-host Container Manager managing isolated {@link ContainerContext} instances.
 *
 * <p>Each host container is identified by a {@code hostId} and is managed independently
 * with its own FileChannel, in-memory chunk index, locks, and offsets.
 * Thread-safe across multiple concurrent hosts.
 */
@Component
public class ContainerManager {

    private static final Logger log = LoggerFactory.getLogger(ContainerManager.class);

    private static final UUID DEFAULT_HOST_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final ConcurrentHashMap<UUID, ContainerContext> contexts = new ConcurrentHashMap<>();
    private volatile UUID primaryHostId = DEFAULT_HOST_ID;

    // ─── Multi-Host Context API ───

    public ContainerContext getOrCreateContext(UUID hostId, Path path) {
        return contexts.computeIfAbsent(hostId, id -> new ContainerContext(id, path));
    }

    public ContainerContext getContext(UUID hostId) {
        ContainerContext ctx = contexts.get(hostId);
        if (ctx == null && hostId.equals(primaryHostId)) {
            return contexts.get(DEFAULT_HOST_ID);
        }
        return ctx;
    }

    public void createContainer(UUID hostId, Path path, long totalSize) {
        ContainerContext ctx = getOrCreateContext(hostId, path);
        ctx.create(totalSize);
        this.primaryHostId = hostId;
    }

    public void openContainer(UUID hostId, Path path) {
        ContainerContext ctx = getOrCreateContext(hostId, path);
        ctx.open();
        this.primaryHostId = hostId;
    }

    public void closeContainer(UUID hostId) {
        ContainerContext ctx = contexts.remove(hostId);
        if (ctx != null) {
            ctx.close();
            log.info("Closed container context for host {}", hostId);
        }
    }

    public void deleteContainer(UUID hostId, Path path) {
        closeContainer(hostId);
        try {
            if (Files.exists(path)) {
                Files.delete(path);
                log.info("Deleted container file for host {}: {}", hostId, path);
            }
        } catch (IOException e) {
            throw new ContainerException("Failed to delete container file for host " + hostId + ": " + path, e);
        }
    }

    public Map<UUID, ContainerContext> getAllContexts() {
        return contexts;
    }

    // ─── Backward-Compatible Single-Container API ───

    public synchronized void createContainer(Path path, long totalSize) {
        createContainer(DEFAULT_HOST_ID, path, totalSize);
    }

    public synchronized void openContainer(Path path) {
        openContainer(DEFAULT_HOST_ID, path);
    }

    public synchronized void closeContainer() {
        closeContainer(primaryHostId);
    }

    public synchronized void deleteContainer(Path path) {
        deleteContainer(primaryHostId, path);
    }

    public synchronized byte[] readAtOffset(long offset, int length) {
        ContainerContext ctx = requirePrimaryContext();
        return ctx.readAtOffset(offset, length);
    }

    public synchronized void writeAtOffset(long offset, byte[] data) {
        ContainerContext ctx = requirePrimaryContext();
        ctx.writeAtOffset(offset, data);
    }

    public synchronized ContainerHeader getHeader() {
        ContainerContext ctx = getContext(primaryHostId);
        return ctx != null ? ctx.getHeader() : null;
    }

    public synchronized void flushHeader() {
        ContainerContext ctx = requirePrimaryContext();
        ctx.flushHeader();
    }

    public synchronized void sync() {
        ContainerContext ctx = requirePrimaryContext();
        ctx.sync();
    }

    public synchronized boolean isOpen() {
        ContainerContext ctx = getContext(primaryHostId);
        return ctx != null && ctx.isOpen();
    }

    public synchronized Path getContainerPath() {
        ContainerContext ctx = getContext(primaryHostId);
        return ctx != null ? ctx.getContainerPath() : null;
    }

    public synchronized boolean verifyIntegrity() {
        ContainerContext ctx = requirePrimaryContext();
        try {
            long fileSize = Files.size(ctx.getContainerPath());
            return fileSize >= ctx.getHeader().getTotalSize();
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized void resizeContainer(long newTotalSize) {
        ContainerContext ctx = requirePrimaryContext();
        if (newTotalSize < ctx.getHeader().getTotalSize()) {
            throw new ContainerException("Cannot shrink container. Current: " +
                    ctx.getHeader().getTotalSize() + ", requested: " + newTotalSize);
        }
        if (newTotalSize == ctx.getHeader().getTotalSize()) {
            return;
        }

        ctx.getRwLock().writeLock().lock();
        try {
            ctx.writeAtOffset(newTotalSize - 1, new byte[]{0});
            ctx.getHeader().setTotalSize(newTotalSize);
            ctx.flushHeader();
            ctx.sync();
        } finally {
            ctx.getRwLock().writeLock().unlock();
        }
    }

    public synchronized void acquireLock() {
        // Handled via ContainerContext write lock
    }

    public synchronized void releaseLock() {
        // Handled via ContainerContext write lock
    }

    private ContainerContext requirePrimaryContext() {
        ContainerContext ctx = getContext(primaryHostId);
        if (ctx == null || !ctx.isOpen()) {
            throw new ContainerException("Container is not open");
        }
        return ctx;
    }
}
