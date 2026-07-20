package com.neurovault.backend.upload;

import com.neurovault.backend.dto.UploadProgressResponse;
import com.neurovault.backend.dto.UploadResponse;
import com.neurovault.backend.encryption.AesEncryptionService;
import com.neurovault.backend.encryption.ChunkData;
import com.neurovault.backend.encryption.ChunkEngine;
import com.neurovault.backend.encryption.ChunkStatus;
import com.neurovault.backend.encryption.EncryptionResult;
import com.neurovault.backend.encryption.FileEngineConfig;
import com.neurovault.backend.encryption.IntegrityService;
import com.neurovault.backend.encryption.RsaKeyService;
import com.neurovault.backend.entity.Chunk;
import com.neurovault.backend.entity.FileMetadata;
import com.neurovault.backend.entity.UploadSession;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.BadRequestException;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.repository.ChunkRepository;
import com.neurovault.backend.repository.FileMetadataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;
import java.util.UUID;

/**
 * Core orchestrator for the file upload pipeline.
 *
 * <p>Implements the full upload workflow:
 * <ol>
 *   <li>Receive file from client</li>
 *   <li>Generate AES-256 key and RSA-4096 key pair</li>
 *   <li>Encrypt the file with AES-256-GCM</li>
 *   <li>Split encrypted file into 4 MB chunks</li>
 *   <li>Generate chunk metadata (SHA-256, CRC32, etc.)</li>
 *   <li>Persist file metadata and chunk records</li>
 *   <li>Create upload session</li>
 *   <li>Enqueue chunks for upload to hosts</li>
 *   <li>Process upload queue (simulated for now)</li>
 * </ol>
 * </p>
 */
@Service
@Slf4j
public class UploadService {

    private final AesEncryptionService aesEncryptionService;
    private final RsaKeyService rsaKeyService;
    private final IntegrityService integrityService;
    private final ChunkEngine chunkEngine;
    private final UploadSessionManager sessionManager;
    private final UploadQueue uploadQueue;
    private final FileMetadataRepository fileMetadataRepository;
    private final ChunkRepository chunkRepository;
    private final FileEngineConfig config;

    public UploadService(
            AesEncryptionService aesEncryptionService,
            RsaKeyService rsaKeyService,
            IntegrityService integrityService,
            ChunkEngine chunkEngine,
            UploadSessionManager sessionManager,
            UploadQueue uploadQueue,
            FileMetadataRepository fileMetadataRepository,
            ChunkRepository chunkRepository,
            FileEngineConfig config) {
        this.aesEncryptionService = aesEncryptionService;
        this.rsaKeyService = rsaKeyService;
        this.integrityService = integrityService;
        this.chunkEngine = chunkEngine;
        this.sessionManager = sessionManager;
        this.uploadQueue = uploadQueue;
        this.fileMetadataRepository = fileMetadataRepository;
        this.chunkRepository = chunkRepository;
        this.config = config;
    }

    /**
     * Initiates the full upload pipeline for a file.
     *
     * @param file   the multipart file to upload
     * @param user   the authenticated user
     * @return an {@link UploadResponse} containing session and file metadata
     * @throws BadRequestException if the file is empty or processing fails
     */
    @Transactional
    public UploadResponse initiateUpload(MultipartFile file, User user) {
        if (file.isEmpty()) {
            throw new BadRequestException("File is empty");
        }

        try {
            String originalFileName = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "unnamed";
            long originalSize = file.getSize();

            log.info("Starting upload pipeline for '{}' ({} bytes) by user {}",
                    originalFileName, originalSize, user.getId());

            // Step 1: Generate cryptographic keys
            SecretKey aesKey = aesEncryptionService.generateKey();
            KeyPair rsaKeyPair = rsaKeyService.generateKeyPair();

            // Step 2: Encrypt the file
            ByteArrayOutputStream encryptedStream = new ByteArrayOutputStream();
            EncryptionResult encResult = aesEncryptionService.encrypt(
                    file.getInputStream(), encryptedStream, aesKey);

            byte[] encryptedBytes = encryptedStream.toByteArray();
            log.debug("Encrypted file: {} bytes -> {} bytes", originalSize, encryptedBytes.length);

            // Step 3: Wrap AES key with RSA public key
            String encryptedAesKey = rsaKeyService.wrapKey(aesKey, rsaKeyPair.getPublic());

            // Step 4: Split encrypted file into chunks
            List<ChunkData> chunks = chunkEngine.splitFile(
                    new ByteArrayInputStream(encryptedBytes),
                    encryptedBytes.length,
                    user.getId());

            // Step 5: Persist file metadata
            FileMetadata fileMetadata = FileMetadata.builder()
                    .owner(user)
                    .name(originalFileName)
                    .path("/" + originalFileName)
                    .sizeBytes(originalSize)
                    .mimeType(file.getContentType())
                    .encryptedAesKey(encryptedAesKey)
                    .fileHash(encResult.plaintextHash())
                    .build();

            FileMetadata savedFile = fileMetadataRepository.save(fileMetadata);
            log.info("Persisted file metadata: id={}, hash={}", savedFile.getId(), savedFile.getFileHash());

            // Step 6: Persist chunk metadata
            for (ChunkData chunkData : chunks) {
                Chunk chunkEntity = Chunk.builder()
                        .file(savedFile)
                        .chunkIndex(chunkData.getChunkIndex())
                        .sizeBytes(chunkData.getChunkSize())
                        .checksum(chunkData.getSha256Hash())
                        .status(Chunk.Status.ACTIVE)
                        .build();
                chunkRepository.save(chunkEntity);
            }

            // Step 7: Create upload session
            UploadSession session = sessionManager.createSession(
                    user, originalFileName, originalSize, chunks.size());

            // Step 8: Enqueue chunks for upload and process them
            for (ChunkData chunkData : chunks) {
                ChunkUploadTask task = ChunkUploadTask.builder()
                        .sessionId(session.getId())
                        .chunkId(chunkData.getChunkId())
                        .chunkIndex(chunkData.getChunkIndex())
                        .data(chunkData.getData())
                        .build();
                uploadQueue.enqueue(task);
            }

            // Step 9: Process the upload queue (simulated — writes to temp dir)
            processUploadQueue(session.getId());

            // Step 10: Update session status
            sessionManager.updateStatus(session.getId(), UploadSession.Status.COMPLETED);

            // Step 11: Save encrypted chunks to temp directory for later download
            saveChunksToTempDir(savedFile.getId(), chunks);

            log.info("Upload pipeline completed: sessionId={}, fileId={}, chunks={}",
                    session.getId(), savedFile.getId(), chunks.size());

            return UploadResponse.builder()
                    .uploadId(session.getId())
                    .fileId(savedFile.getId())
                    .fileName(originalFileName)
                    .fileSize(originalSize)
                    .totalChunks(chunks.size())
                    .status(UploadSession.Status.COMPLETED.name())
                    .createdAt(session.getCreatedAt())
                    .encryptedAesKey(encryptedAesKey)
                    .build();

        } catch (GeneralSecurityException e) {
            log.error("Cryptographic error during upload", e);
            throw new BadRequestException("Encryption failed: " + e.getMessage());
        } catch (IOException e) {
            log.error("I/O error during upload", e);
            throw new BadRequestException("File processing failed: " + e.getMessage());
        }
    }

    /**
     * Returns the current progress of an upload session.
     *
     * @param uploadId the upload session ID
     * @return the progress response
     */
    public UploadProgressResponse getProgress(UUID uploadId) {
        UploadSession session = sessionManager.getSession(uploadId);

        int completed = uploadQueue.completedCount(uploadId);
        int failed = uploadQueue.failedCount(uploadId);
        int total = session.getTotalChunks();
        double percent = total > 0 ? (completed * 100.0 / total) : 0.0;

        return UploadProgressResponse.builder()
                .uploadId(uploadId)
                .fileName(session.getFileName())
                .totalChunks(total)
                .completedChunks(completed)
                .failedChunks(failed)
                .progressPercent(Math.round(percent * 100.0) / 100.0)
                .status(session.getStatus().name())
                .build();
    }

    /**
     * Retries failed chunk uploads for a session.
     *
     * @param uploadId the upload session ID
     * @return the updated upload response
     */
    @Transactional
    public UploadResponse retryUpload(UUID uploadId) {
        UploadSession session = sessionManager.getSession(uploadId);

        if (session.getStatus() == UploadSession.Status.COMPLETED) {
            throw new BadRequestException("Upload session is already completed");
        }

        // Re-enqueue failed tasks
        List<ChunkUploadTask> failedTasks = uploadQueue.getAllTasks(uploadId).stream()
                .filter(t -> t.getStatus() == ChunkStatus.FAILED)
                .toList();

        if (failedTasks.isEmpty()) {
            throw new BadRequestException("No failed chunks to retry");
        }

        sessionManager.updateStatus(uploadId, UploadSession.Status.UPLOADING);

        for (ChunkUploadTask task : failedTasks) {
            uploadQueue.retryTask(task);
        }

        // Process retried tasks
        processUploadQueue(uploadId);

        // Check if all tasks completed
        int failed = uploadQueue.failedCount(uploadId);
        UploadSession.Status newStatus = failed == 0
                ? UploadSession.Status.COMPLETED
                : UploadSession.Status.FAILED;
        sessionManager.updateStatus(uploadId, newStatus);

        return UploadResponse.builder()
                .uploadId(session.getId())
                .fileName(session.getFileName())
                .fileSize(session.getFileSize())
                .totalChunks(session.getTotalChunks())
                .status(newStatus.name())
                .createdAt(session.getCreatedAt())
                .build();
    }

    /**
     * Cancels an upload session and cleans up resources.
     *
     * @param uploadId the upload session ID
     */
    @Transactional
    public void cancelUpload(UUID uploadId) {
        UploadSession session = sessionManager.getSession(uploadId);

        if (session.getStatus() == UploadSession.Status.COMPLETED) {
            throw new BadRequestException("Cannot cancel a completed upload");
        }

        sessionManager.updateStatus(uploadId, UploadSession.Status.FAILED);
        uploadQueue.clearSession(uploadId);

        log.info("Cancelled upload session {}", uploadId);
    }

    /**
     * Processes all pending tasks in the upload queue for a session.
     * In this implementation, "uploading" means marking tasks as completed
     * since the actual host data plane is not yet implemented.
     */
    private void processUploadQueue(UUID sessionId) {
        ChunkUploadTask task;
        while ((task = uploadQueue.dequeue(sessionId)) != null) {
            try {
                // Simulate successful upload (in production, this would send to hosts)
                task.setStatus(ChunkStatus.UPLOADED);
                log.debug("Processed chunk upload: session={}, chunk={}",
                        sessionId, task.getChunkIndex());
            } catch (Exception e) {
                log.error("Failed to upload chunk {}", task.getChunkIndex(), e);
                task.setStatus(ChunkStatus.FAILED);
            }
        }
    }

    /**
     * Saves encrypted chunk data to the temp directory for later download retrieval.
     * Each chunk is saved as {@code <tempDir>/<fileId>/<chunkIndex>.bin}.
     */
    private void saveChunksToTempDir(UUID fileId, List<ChunkData> chunks) throws IOException {
        Path chunkDir = Path.of(config.getTempDir(), fileId.toString());
        Files.createDirectories(chunkDir);

        for (ChunkData chunk : chunks) {
            Path chunkFile = chunkDir.resolve(chunk.getChunkIndex() + ".bin");
            try (FileOutputStream fos = new FileOutputStream(chunkFile.toFile())) {
                fos.write(chunk.getData());
            }
        }
        log.debug("Saved {} chunks to {}", chunks.size(), chunkDir);
    }
}
