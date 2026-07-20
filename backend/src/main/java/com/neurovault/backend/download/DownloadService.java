package com.neurovault.backend.download;

import com.neurovault.backend.dto.DownloadProgressResponse;
import com.neurovault.backend.dto.DownloadResponse;
import com.neurovault.backend.encryption.AesEncryptionService;
import com.neurovault.backend.encryption.ChunkData;
import com.neurovault.backend.encryption.ChunkEngine;
import com.neurovault.backend.encryption.ChunkStatus;
import com.neurovault.backend.encryption.FileEngineConfig;
import com.neurovault.backend.encryption.IntegrityService;
import com.neurovault.backend.encryption.RsaKeyService;
import com.neurovault.backend.entity.Chunk;
import com.neurovault.backend.entity.DownloadSession;
import com.neurovault.backend.entity.FileMetadata;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.BadRequestException;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.repository.ChunkRepository;
import com.neurovault.backend.repository.FileMetadataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Core orchestrator for the file download pipeline.
 *
 * <p>Implements the full download workflow:
 * <ol>
 *   <li>Receive download request with file ID and RSA private key</li>
 *   <li>Retrieve file metadata and chunk metadata from coordinator</li>
 *   <li>Download encrypted chunks from temp storage (simulated host retrieval)</li>
 *   <li>Verify integrity of each chunk (SHA-256)</li>
 *   <li>Merge chunks in correct order</li>
 *   <li>Unwrap AES key using RSA private key</li>
 *   <li>Decrypt merged encrypted data</li>
 *   <li>Verify final file hash matches original</li>
 *   <li>Return original file</li>
 * </ol>
 * </p>
 */
@Service
@Slf4j
public class DownloadService {

    private final AesEncryptionService aesEncryptionService;
    private final RsaKeyService rsaKeyService;
    private final IntegrityService integrityService;
    private final ChunkEngine chunkEngine;
    private final DownloadSessionManager sessionManager;
    private final FileMetadataRepository fileMetadataRepository;
    private final ChunkRepository chunkRepository;
    private final FileEngineConfig config;

    public DownloadService(
            AesEncryptionService aesEncryptionService,
            RsaKeyService rsaKeyService,
            IntegrityService integrityService,
            ChunkEngine chunkEngine,
            DownloadSessionManager sessionManager,
            FileMetadataRepository fileMetadataRepository,
            ChunkRepository chunkRepository,
            FileEngineConfig config) {
        this.aesEncryptionService = aesEncryptionService;
        this.rsaKeyService = rsaKeyService;
        this.integrityService = integrityService;
        this.chunkEngine = chunkEngine;
        this.sessionManager = sessionManager;
        this.fileMetadataRepository = fileMetadataRepository;
        this.chunkRepository = chunkRepository;
        this.config = config;
    }

    /**
     * Initiates a download session for a file.
     *
     * @param fileId the file metadata ID
     * @param user   the authenticated user requesting the download
     * @return a {@link DownloadResponse} with session and file info
     */
    @Transactional
    public DownloadResponse initiateDownload(UUID fileId, User user) {
        FileMetadata file = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found: " + fileId));

        // Verify ownership
        if (!file.getOwner().getId().equals(user.getId())) {
            throw new BadRequestException("Access denied: you do not own this file");
        }

        List<Chunk> chunks = chunkRepository.findByFileId(fileId);
        DownloadSession session = sessionManager.createSession(user, file);

        log.info("Initiated download session {} for file '{}' ({} chunks)",
                session.getId(), file.getName(), chunks.size());

        return DownloadResponse.builder()
                .downloadId(session.getId())
                .fileId(fileId)
                .fileName(file.getName())
                .fileSize(file.getSizeBytes())
                .totalChunks(chunks.size())
                .status(session.getStatus().name())
                .build();
    }

    /**
     * Downloads and reconstructs the original file.
     *
     * <p>This method performs the full download pipeline:
     * download chunks → verify → merge → decrypt → verify final hash.</p>
     *
     * @param fileId        the file metadata ID
     * @param user          the authenticated user
     * @param privateKeyB64 the Base64-encoded RSA private key for unwrapping the AES key
     * @return the decrypted original file as a {@link Resource}
     */
    @Transactional
    public Resource downloadFile(UUID fileId, User user, String privateKeyB64) {
        FileMetadata file = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found: " + fileId));

        if (!file.getOwner().getId().equals(user.getId())) {
            throw new BadRequestException("Access denied: you do not own this file");
        }

        DownloadSession session = sessionManager.createSession(user, file);
        sessionManager.updateStatus(session.getId(), DownloadSession.Status.DOWNLOADING);

        try {
            // Step 1: Retrieve chunk metadata
            List<Chunk> chunkEntities = chunkRepository.findByFileId(fileId);
            chunkEntities.sort(Comparator.comparingInt(Chunk::getChunkIndex));

            if (chunkEntities.isEmpty()) {
                throw new BadRequestException("No chunks found for file: " + fileId);
            }

            // Step 2: Download and verify chunks from temp storage
            List<ChunkData> downloadedChunks = downloadAndVerifyChunks(fileId, chunkEntities);

            // Step 3: Merge chunks
            byte[] encryptedData = chunkEngine.mergeChunksToBytes(downloadedChunks);
            log.debug("Merged {} chunks into {} bytes of encrypted data",
                    downloadedChunks.size(), encryptedData.length);

            // Step 4: Unwrap AES key
            PrivateKey rsaPrivateKey = decodePrivateKey(privateKeyB64);
            SecretKey aesKey = rsaKeyService.unwrapKey(file.getEncryptedAesKey(), rsaPrivateKey);

            // Step 5: Decrypt the merged data
            ByteArrayOutputStream decryptedStream = new ByteArrayOutputStream();
            aesEncryptionService.decrypt(
                    new ByteArrayInputStream(encryptedData), decryptedStream, aesKey);
            byte[] decryptedBytes = decryptedStream.toByteArray();

            // Step 6: Verify final file hash
            String computedHash = integrityService.computeSha256(decryptedBytes);
            if (!computedHash.equalsIgnoreCase(file.getFileHash())) {
                sessionManager.updateStatus(session.getId(), DownloadSession.Status.FAILED);
                throw new BadRequestException(
                        "File integrity verification failed: expected hash " + file.getFileHash()
                                + " but computed " + computedHash);
            }

            sessionManager.updateStatus(session.getId(), DownloadSession.Status.COMPLETED);
            log.info("Download pipeline completed: sessionId={}, fileId={}, size={} bytes",
                    session.getId(), fileId, decryptedBytes.length);

            return new ByteArrayResource(decryptedBytes);

        } catch (GeneralSecurityException e) {
            sessionManager.updateStatus(session.getId(), DownloadSession.Status.FAILED);
            log.error("Cryptographic error during download", e);
            throw new BadRequestException("Decryption failed: " + e.getMessage());
        } catch (IOException e) {
            sessionManager.updateStatus(session.getId(), DownloadSession.Status.FAILED);
            log.error("I/O error during download", e);
            throw new BadRequestException("File reconstruction failed: " + e.getMessage());
        }
    }

    /**
     * Returns the current progress of a download session.
     *
     * @param downloadId the download session ID
     * @return the progress response
     */
    public DownloadProgressResponse getProgress(UUID downloadId) {
        DownloadSession session = sessionManager.getSession(downloadId);
        FileMetadata file = session.getFile();
        List<Chunk> chunks = chunkRepository.findByFileId(file.getId());

        int total = chunks.size();
        int completed = (int) chunks.stream()
                .filter(c -> c.getStatus() == Chunk.Status.ACTIVE)
                .count();
        double percent = total > 0 ? (completed * 100.0 / total) : 0.0;

        return DownloadProgressResponse.builder()
                .downloadId(downloadId)
                .fileName(file.getName())
                .totalChunks(total)
                .completedChunks(completed)
                .progressPercent(Math.round(percent * 100.0) / 100.0)
                .status(session.getStatus().name())
                .build();
    }

    /**
     * Retries a failed download.
     *
     * @param downloadId    the download session ID
     * @param privateKeyB64 the Base64-encoded RSA private key
     * @return the download response
     */
    @Transactional
    public DownloadResponse retryDownload(UUID downloadId, String privateKeyB64) {
        DownloadSession session = sessionManager.getSession(downloadId);

        if (session.getStatus() == DownloadSession.Status.COMPLETED) {
            throw new BadRequestException("Download is already completed");
        }

        FileMetadata file = session.getFile();
        // Re-attempt the download
        downloadFile(file.getId(), session.getUser(), privateKeyB64);

        return DownloadResponse.builder()
                .downloadId(downloadId)
                .fileId(file.getId())
                .fileName(file.getName())
                .fileSize(file.getSizeBytes())
                .totalChunks(chunkRepository.findByFileId(file.getId()).size())
                .status(DownloadSession.Status.COMPLETED.name())
                .build();
    }

    /**
     * Downloads encrypted chunk files from temp storage and verifies their integrity.
     */
    private List<ChunkData> downloadAndVerifyChunks(UUID fileId, List<Chunk> chunkEntities)
            throws IOException {

        List<ChunkData> chunks = new ArrayList<>();
        Path chunkDir = Path.of(config.getTempDir(), fileId.toString());

        for (Chunk entity : chunkEntities) {
            Path chunkPath = chunkDir.resolve(entity.getChunkIndex() + ".bin");

            if (!Files.exists(chunkPath)) {
                throw new BadRequestException(
                        "Chunk file missing: " + chunkPath + " (index=" + entity.getChunkIndex() + ")");
            }

            byte[] chunkBytes = Files.readAllBytes(chunkPath);

            // Verify chunk integrity
            String computedHash = integrityService.computeSha256(chunkBytes);
            if (!computedHash.equalsIgnoreCase(entity.getChecksum())) {
                log.error("Chunk integrity check failed for chunk {} of file {}: expected={}, actual={}",
                        entity.getChunkIndex(), fileId, entity.getChecksum(), computedHash);
                throw new BadRequestException(
                        "Corrupted chunk detected at index " + entity.getChunkIndex());
            }

            ChunkData chunkData = ChunkData.builder()
                    .chunkId(entity.getId())
                    .chunkIndex(entity.getChunkIndex())
                    .chunkSize(chunkBytes.length)
                    .sha256Hash(computedHash)
                    .checksum(integrityService.computeChecksum(chunkBytes))
                    .data(chunkBytes)
                    .status(ChunkStatus.VERIFIED)
                    .build();

            chunks.add(chunkData);
        }

        log.info("Downloaded and verified {} chunks for file {}", chunks.size(), fileId);
        return chunks;
    }

    /**
     * Decodes a Base64-encoded PKCS#8 RSA private key.
     */
    private PrivateKey decodePrivateKey(String base64Key) throws GeneralSecurityException {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }
}
