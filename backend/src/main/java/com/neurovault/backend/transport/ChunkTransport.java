package com.neurovault.backend.transport;

import java.io.InputStream;
import java.util.UUID;

/**
 * Common abstraction for chunk data plane transport operations.
 * Unifies local container direct I/O, remote HTTP microserver transfers,
 * and peer-to-peer data plane streaming.
 */
public interface ChunkTransport {

    /**
     * Opens an input stream to read encrypted chunk bytes from the specified host.
     */
    InputStream openReadStream(UUID hostId, UUID chunkId, String capabilityToken);

    /**
     * Streams chunk bytes from sourceStream directly to the target host container.
     */
    void streamToTarget(UUID hostId, UUID chunkId, UUID ownerId, InputStream sourceStream,
                        long size, String expectedSha256, String capabilityToken);

    /**
     * Verifies physical chunk existence and SHA-256 hash on the target host.
     */
    boolean verifyChunkIntegrity(UUID hostId, UUID chunkId, String expectedSha256);

    /**
     * Returns the transport identifier (e.g. LOCAL_CONTAINER, HTTP, WEBRTC).
     */
    String getTransportType();
}
