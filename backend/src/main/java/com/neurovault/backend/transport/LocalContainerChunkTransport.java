package com.neurovault.backend.transport;

import com.neurovault.backend.storage.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;

/**
 * High-performance local container implementation of {@link ChunkTransport}.
 * Directly streams bytes between binary container files on disk using bounded buffers.
 */
@Component("localContainerChunkTransport")
public class LocalContainerChunkTransport implements ChunkTransport {

    private static final Logger log = LoggerFactory.getLogger(LocalContainerChunkTransport.class);

    private final StorageService storageService;

    public LocalContainerChunkTransport(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public InputStream openReadStream(UUID hostId, UUID chunkId, String capabilityToken) {
        log.debug("Opening local container read stream for host {} chunk {}", hostId, chunkId);
        return storageService.readChunkStream(hostId, chunkId);
    }

    @Override
    public void streamToTarget(UUID hostId, UUID chunkId, UUID ownerId, InputStream sourceStream,
                               long size, String expectedSha256, String capabilityToken) {
        log.debug("Streaming chunk {} ({} bytes) to local host container {}", chunkId, size, hostId);
        storageService.storeChunkStream(hostId, chunkId, ownerId, sourceStream, size, expectedSha256);
    }

    @Override
    public boolean verifyChunkIntegrity(UUID hostId, UUID chunkId, String expectedSha256) {
        return storageService.verifyChunkIntegrity(hostId, chunkId, expectedSha256);
    }

    @Override
    public String getTransportType() {
        return "LOCAL_CONTAINER";
    }
}
