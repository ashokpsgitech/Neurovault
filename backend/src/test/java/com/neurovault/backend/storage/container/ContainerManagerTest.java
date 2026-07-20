package com.neurovault.backend.storage.container;

import com.neurovault.backend.storage.exception.ContainerException;
import com.neurovault.backend.storage.model.ContainerHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContainerManager}.
 * Uses a temporary directory for all container file operations.
 */
class ContainerManagerTest {

    @TempDir
    Path tempDir;

    private ContainerManager containerManager;
    private Path containerPath;

    @BeforeEach
    void setUp() {
        containerManager = new ContainerManager();
        containerPath = tempDir.resolve("test-host").resolve("storage.container");
    }

    @AfterEach
    void tearDown() {
        if (containerManager.isOpen()) {
            containerManager.closeContainer();
        }
    }

    @Test
    void createContainer_shouldCreateFileWithCorrectSize() {
        long size = 10 * 1024 * 1024; // 10 MB
        containerManager.createContainer(containerPath, size);

        assertTrue(Files.exists(containerPath));
        assertTrue(containerManager.isOpen());

        ContainerHeader header = containerManager.getHeader();
        assertNotNull(header);
        assertEquals(size, header.getTotalSize());
        assertEquals(0, header.getUsedSize());
        assertEquals(0, header.getChunkCount());
        assertEquals(ContainerHeader.CURRENT_VERSION, header.getVersion());
    }

    @Test
    void createContainer_shouldFailIfAlreadyExists() {
        long size = 1024 * 1024; // 1 MB
        containerManager.createContainer(containerPath, size);
        containerManager.closeContainer();

        assertThrows(ContainerException.class, () ->
                containerManager.createContainer(containerPath, size));
    }

    @Test
    void openContainer_shouldReadExistingHeader() {
        long size = 5 * 1024 * 1024; // 5 MB
        containerManager.createContainer(containerPath, size);
        containerManager.closeContainer();

        assertFalse(containerManager.isOpen());

        containerManager.openContainer(containerPath);

        assertTrue(containerManager.isOpen());
        ContainerHeader header = containerManager.getHeader();
        assertEquals(size, header.getTotalSize());
    }

    @Test
    void openContainer_shouldFailIfNotExists() {
        Path noSuchPath = tempDir.resolve("nonexistent.container");
        assertThrows(ContainerException.class, () ->
                containerManager.openContainer(noSuchPath));
    }

    @Test
    void closeContainer_shouldCloseFileChannel() {
        containerManager.createContainer(containerPath, 1024 * 1024);
        assertTrue(containerManager.isOpen());

        containerManager.closeContainer();
        assertFalse(containerManager.isOpen());
    }

    @Test
    void deleteContainer_shouldRemoveFile() {
        containerManager.createContainer(containerPath, 1024 * 1024);
        assertTrue(Files.exists(containerPath));

        containerManager.deleteContainer(containerPath);
        assertFalse(Files.exists(containerPath));
        assertFalse(containerManager.isOpen());
    }

    @Test
    void resizeContainer_shouldExpandSize() {
        long initialSize = 1024 * 1024; // 1 MB
        long newSize = 5 * 1024 * 1024; // 5 MB

        containerManager.createContainer(containerPath, initialSize);
        assertEquals(initialSize, containerManager.getHeader().getTotalSize());

        containerManager.resizeContainer(newSize);
        assertEquals(newSize, containerManager.getHeader().getTotalSize());
    }

    @Test
    void resizeContainer_shouldRejectShrinking() {
        long initialSize = 5 * 1024 * 1024;
        long smallerSize = 1 * 1024 * 1024;

        containerManager.createContainer(containerPath, initialSize);

        assertThrows(ContainerException.class, () ->
                containerManager.resizeContainer(smallerSize));
    }

    @Test
    void readWriteAtOffset_shouldRoundTrip() {
        containerManager.createContainer(containerPath, 1024 * 1024);

        byte[] testData = "Hello, NeuroVault!".getBytes();
        long offset = 512; // Somewhere in the data region

        containerManager.writeAtOffset(offset, testData);
        byte[] readData = containerManager.readAtOffset(offset, testData.length);

        assertArrayEquals(testData, readData);
    }

    @Test
    void readWriteAtOffset_shouldHandleLargeData() {
        containerManager.createContainer(containerPath, 2 * 1024 * 1024);

        // Write 64KB of data
        byte[] largeData = new byte[64 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        long offset = ContainerHeader.HEADER_SIZE + ContainerHeader.DEFAULT_METADATA_REGION_SIZE;
        containerManager.writeAtOffset(offset, largeData);
        byte[] readData = containerManager.readAtOffset(offset, largeData.length);

        assertArrayEquals(largeData, readData);
    }

    @Test
    void verifyIntegrity_shouldPassForValidContainer() {
        containerManager.createContainer(containerPath, 1024 * 1024);
        assertTrue(containerManager.verifyIntegrity());
    }

    @Test
    void acquireAndReleaseLock_shouldWork() {
        containerManager.createContainer(containerPath, 1024 * 1024);

        assertDoesNotThrow(() -> containerManager.acquireLock());
        assertDoesNotThrow(() -> containerManager.releaseLock());
    }

    @Test
    void operations_shouldFailWhenNotOpen() {
        assertThrows(ContainerException.class, () ->
                containerManager.readAtOffset(0, 10));

        assertThrows(ContainerException.class, () ->
                containerManager.writeAtOffset(0, new byte[10]));

        assertThrows(ContainerException.class, () ->
                containerManager.resizeContainer(2048));

        assertThrows(ContainerException.class, () ->
                containerManager.verifyIntegrity());
    }

    @Test
    void headerPersistence_shouldSurviveCloseAndReopen() {
        long size = 2 * 1024 * 1024;
        containerManager.createContainer(containerPath, size);

        // Modify header state
        ContainerHeader header = containerManager.getHeader();
        header.setUsedSize(1024);
        header.setChunkCount(5);
        containerManager.flushHeader();
        containerManager.closeContainer();

        // Reopen and verify
        containerManager.openContainer(containerPath);
        ContainerHeader reopened = containerManager.getHeader();
        assertEquals(size, reopened.getTotalSize());
        assertEquals(1024, reopened.getUsedSize());
        assertEquals(5, reopened.getChunkCount());
    }
}
