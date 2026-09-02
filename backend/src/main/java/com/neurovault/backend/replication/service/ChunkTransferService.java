package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.storage.dto.ChunkMetadataDto;
import com.neurovault.backend.storage.dto.ChunkTransferResponse;
import com.neurovault.backend.storage.dto.StoreChunkRequest;
import com.neurovault.backend.storage.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Coordinator-side service that orchestrates the physical transfer of chunk data
 * between storage hosts during self-healing repairs.
 *
 * <p><strong>Architecture Rule:</strong> The Coordinator (this service) issues transfer
 * commands but NEVER proxies chunk bytes through its own memory in a distributed setup.
 * In the current single-process architecture, both source and destination containers
 * are managed by the same JVM, so transfers go through {@link StorageService}.
 *
 * <p>Flow:
 * <pre>
 * Coordinator ── transfer command ──► Source Host
 * Source Host ── actual chunk data ──► Destination Host
 * </pre>
 *
 * @author NeuroVault Team
 */
@Slf4j
@Service
public class ChunkTransferService {

    private final StorageService storageService;

    public ChunkTransferService(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * Executes a chunk transfer from source host to destination host.
     *
     * <p>Steps:
     * <ol>
     *   <li>Read chunk data from source host's container</li>
     *   <li>Verify source data integrity against expected checksum</li>
     *   <li>Store chunk data in destination host's container</li>
     *   <li>Verify destination data integrity</li>
     *   <li>Return result</li>
     * </ol>
     *
     * @param sourceHost       the host holding the source replica
     * @param destinationHost  the host to receive the new replica
     * @param chunkId          the chunk ID to transfer
     * @param expectedChecksum the expected SHA-256 hash of the chunk data
     * @param ownerId          the owner of the chunk data
     * @return transfer result with success/failure and checksums
     */
    public ChunkTransferResponse transferChunk(Host sourceHost, Host destinationHost,
                                                UUID chunkId, String expectedChecksum,
                                                UUID ownerId) {
        log.info("Initiating chunk transfer: chunk={}, source={} ({}), destination={} ({})",
                chunkId, sourceHost.getName(), sourceHost.getId(),
                destinationHost.getName(), destinationHost.getId());

        try {
            // Step 1: Read chunk data from source host
            byte[] chunkData = storageService.readChunk(sourceHost.getId(), chunkId);

            if (chunkData == null || chunkData.length == 0) {
                log.error("Chunk {} not found on source host {}", chunkId, sourceHost.getName());
                return ChunkTransferResponse.builder()
                        .success(false)
                        .chunkId(chunkId)
                        .sourceHostId(sourceHost.getId())
                        .destinationHostId(destinationHost.getId())
                        .errorMessage("Chunk data not found on source host")
                        .build();
            }

            log.debug("Read {} bytes for chunk {} from source host {}",
                    chunkData.length, chunkId, sourceHost.getName());

            // Step 2: Verify source integrity
            if (expectedChecksum != null && !expectedChecksum.isEmpty()) {
                String sourceHash = computeSha256(chunkData);
                if (!sourceHash.equalsIgnoreCase(expectedChecksum)) {
                    log.error("Source integrity check FAILED for chunk {}: expected={}, actual={}",
                            chunkId, expectedChecksum, sourceHash);
                    return ChunkTransferResponse.builder()
                            .success(false)
                            .chunkId(chunkId)
                            .sourceHostId(sourceHost.getId())
                            .destinationHostId(destinationHost.getId())
                            .errorMessage("Source data integrity verification failed")
                            .build();
                }
                log.debug("Source integrity verified for chunk {}", chunkId);
            }

            // Step 3: Store chunk on destination host
            StoreChunkRequest storeRequest = StoreChunkRequest.builder()
                    .chunkId(chunkId)
                    .ownerId(ownerId)
                    .data(chunkData)
                    .build();

            ChunkMetadataDto destMetadata = storageService.storeChunk(
                    destinationHost.getId(), storeRequest);

            // Step 4: Verify destination integrity
            String destinationChecksum = destMetadata.getSha256Hash();

            if (expectedChecksum != null && !expectedChecksum.isEmpty()
                    && !destinationChecksum.equalsIgnoreCase(expectedChecksum)) {
                log.error("Destination integrity check FAILED for chunk {}: expected={}, destination={}",
                        chunkId, expectedChecksum, destinationChecksum);
                return ChunkTransferResponse.builder()
                        .success(false)
                        .chunkId(chunkId)
                        .sourceHostId(sourceHost.getId())
                        .destinationHostId(destinationHost.getId())
                        .destinationChecksum(destinationChecksum)
                        .errorMessage("Destination data integrity verification failed")
                        .build();
            }

            log.info("Chunk {} successfully transferred: {} → {} (checksum={})",
                    chunkId, sourceHost.getName(), destinationHost.getName(), destinationChecksum);

            return ChunkTransferResponse.builder()
                    .success(true)
                    .chunkId(chunkId)
                    .sourceHostId(sourceHost.getId())
                    .destinationHostId(destinationHost.getId())
                    .destinationChecksum(destinationChecksum)
                    .build();

        } catch (Exception e) {
            log.error("Transfer failed for chunk {} ({} → {}): {}",
                    chunkId, sourceHost.getName(), destinationHost.getName(), e.getMessage(), e);
            return ChunkTransferResponse.builder()
                    .success(false)
                    .chunkId(chunkId)
                    .sourceHostId(sourceHost.getId())
                    .destinationHostId(destinationHost.getId())
                    .errorMessage("Transfer failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Computes the SHA-256 hash of the given data.
     *
     * @param data the byte array to hash
     * @return lowercase hex-encoded SHA-256 digest
     */
    private String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
