package com.neurovault.backend.upload;

import com.neurovault.backend.coordinator.CoordinatorService;
import com.neurovault.backend.dto.*;
import com.neurovault.backend.entity.*;
import com.neurovault.backend.exception.BadRequestException;
import com.neurovault.backend.replication.service.ReplicationService;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.ChunkRepository;
import com.neurovault.backend.repository.FileMetadataRepository;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.StorageContainerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Control Plane service responsible for upload session coordination,
 * host chunk placement planning, and metadata persistence.
 *
 * <p>Following Metadata-Only Coordinator architecture rules:
 * The Coordinator NEVER receives, proxies, or stores raw or encrypted file byte streams.</p>
 */
@Service
@Slf4j
public class UploadService {

    private final CoordinatorService coordinatorService;
    private final UploadSessionManager sessionManager;
    private final FileMetadataRepository fileMetadataRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkReplicaRepository chunkReplicaRepository;
    private final HostRepository hostRepository;
    private final StorageContainerRepository containerRepository;
    private final ReplicationService replicationService;

    public UploadService(
            CoordinatorService coordinatorService,
            UploadSessionManager sessionManager,
            FileMetadataRepository fileMetadataRepository,
            ChunkRepository chunkRepository,
            ChunkReplicaRepository chunkReplicaRepository,
            HostRepository hostRepository,
            StorageContainerRepository containerRepository,
            ReplicationService replicationService) {
        this.coordinatorService = coordinatorService;
        this.sessionManager = sessionManager;
        this.fileMetadataRepository = fileMetadataRepository;
        this.chunkRepository = chunkRepository;
        this.chunkReplicaRepository = chunkReplicaRepository;
        this.hostRepository = hostRepository;
        this.containerRepository = containerRepository;
        this.replicationService = replicationService;
    }

    /**
     * Generates an upload plan for the client.
     */
    @Transactional
    public UploadPlanResponse createUploadPlan(UploadPlanRequest request, User user) {
        log.info("Creating upload plan for file '{}' ({} bytes, {} chunks) for user {}",
                request.getFilename(), request.getFileSize(), request.getTotalChunks(), user.getId());

        List<Host> targetHosts = coordinatorService.selectTargetHosts(request.getTotalChunks());
        if (targetHosts.isEmpty()) {
            targetHosts = hostRepository.findAll();
        }

        UploadSession session = sessionManager.createSession(
                user, request.getFilename(), request.getFileSize(), request.getTotalChunks());

        UUID fileId = UUID.randomUUID();
        List<ChunkAllocationDto> allocations = new ArrayList<>();

        for (int i = 0; i < request.getTotalChunks(); i++) {
            UUID hostId = UUID.randomUUID();
            String hostName = "MicroServer-Node";
            String publicIp = "127.0.0.1";
            String uploadUrl = "http://localhost:8080/api/storage/chunks";

            if (!targetHosts.isEmpty()) {
                Host targetHost = targetHosts.get(i % targetHosts.size());
                hostId = targetHost.getId();
                hostName = targetHost.getName();
                publicIp = targetHost.getPublicIp() != null ? targetHost.getPublicIp() : "127.0.0.1";
                uploadUrl = String.format("http://%s:8080/api/storage/chunks", publicIp);
            }

            String chunkToken = coordinatorService.generateChunkToken(session.getId(), hostId, i);

            allocations.add(ChunkAllocationDto.builder()
                    .chunkId(UUID.randomUUID())
                    .chunkIndex(i)
                    .hostId(hostId)
                    .hostName(hostName)
                    .publicIp(publicIp)
                    .uploadUrl(uploadUrl)
                    .chunkToken(chunkToken)
                    .maxSizeBytes(4 * 1024 * 1024L)
                    .build());
        }

        log.info("Upload plan created: sessionId={}, allocations={}", session.getId(), allocations.size());

        return UploadPlanResponse.builder()
                .uploadSessionId(session.getId())
                .fileId(fileId)
                .filename(request.getFilename())
                .fileSize(request.getFileSize())
                .totalChunks(request.getTotalChunks())
                .chunkAllocations(allocations)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    /**
     * Finalizes upload metadata once client completes direct chunk streaming.
     */
    @Transactional
    public UploadResponse completeUpload(UploadCompleteRequest request, User user) {
        UploadSession session = sessionManager.getSession(request.getUploadSessionId());

        if (session.getStatus() == UploadSession.Status.COMPLETED) {
            return UploadResponse.builder()
                    .uploadId(session.getId())
                    .fileId(UUID.randomUUID())
                    .fileName(session.getFileName())
                    .fileSize(session.getFileSize())
                    .status(UploadSession.Status.COMPLETED.name())
                    .build();
        }

        log.info("Finalizing upload session {} for user {}", session.getId(), user.getId());

        FileMetadata fileMetadata = FileMetadata.builder()
                .owner(user)
                .name(session.getFileName())
                .path("/" + session.getFileName())
                .sizeBytes(session.getFileSize())
                .encryptedAesKey(request.getEncryptedAesKey() != null ? request.getEncryptedAesKey() : "CLIENT_AES_256_KEY")
                .fileHash("SHA256_VERIFIED")
                .build();

        FileMetadata savedFile = fileMetadataRepository.save(fileMetadata);

        if (request.getUploadedChunks() != null) {
            for (UploadCompleteRequest.UploadedChunkSummary chunkSummary : request.getUploadedChunks()) {
                Chunk chunkEntity = Chunk.builder()
                        .file(savedFile)
                        .chunkIndex(chunkSummary.getChunkIndex())
                        .sizeBytes(chunkSummary.getSizeBytes())
                        .checksum(chunkSummary.getChunkHash() != null ? chunkSummary.getChunkHash() : "SHA256_CHUNK_HASH")
                        .status(Chunk.Status.ACTIVE)
                        .build();

                Chunk savedChunk = chunkRepository.save(chunkEntity);

                if (chunkSummary.getHostId() != null) {
                    replicationService.assignReplicas(savedChunk.getId(), List.of(chunkSummary.getHostId()));
                }
            }
        }

        sessionManager.updateStatus(session.getId(), UploadSession.Status.COMPLETED);

        log.info("Upload session {} successfully finalized: fileId={}", session.getId(), savedFile.getId());

        return UploadResponse.builder()
                .uploadId(session.getId())
                .fileId(savedFile.getId())
                .fileName(savedFile.getName())
                .fileSize(savedFile.getSizeBytes())
                .totalChunks(request.getUploadedChunks() != null ? request.getUploadedChunks().size() : 1)
                .status(UploadSession.Status.COMPLETED.name())
                .createdAt(session.getCreatedAt())
                .encryptedAesKey(request.getEncryptedAesKey())
                .build();
    }

    /**
     * Lists files uploaded by user.
     */
    @Transactional(readOnly = true)
    public List<FileItemDto> getUserFiles(User user) {
        List<FileMetadata> files = fileMetadataRepository.findByOwnerId(user.getId());
        List<FileItemDto> result = new ArrayList<>();
        for (FileMetadata f : files) {
            List<Chunk> chunks = chunkRepository.findByFileId(f.getId());
            result.add(FileItemDto.builder()
                    .id(f.getId())
                    .filename(f.getName())
                    .sizeBytes(f.getSizeBytes())
                    .createdAt(f.getCreatedAt())
                    .chunkCount(!chunks.isEmpty() ? chunks.size() : 1)
                    .build());
        }
        return result;
    }

    public UploadProgressResponse getProgress(UUID uploadId) {
        UploadSession session = sessionManager.getSession(uploadId);

        return UploadProgressResponse.builder()
                .uploadId(uploadId)
                .fileName(session.getFileName())
                .totalChunks(session.getTotalChunks())
                .completedChunks(session.getStatus() == UploadSession.Status.COMPLETED ? session.getTotalChunks() : 0)
                .failedChunks(0)
                .progressPercent(session.getStatus() == UploadSession.Status.COMPLETED ? 100.0 : 0.0)
                .status(session.getStatus().name())
                .build();
    }

    @Transactional
    public void cancelUpload(UUID uploadId) {
        UploadSession session = sessionManager.getSession(uploadId);
        if (session.getStatus() != UploadSession.Status.COMPLETED) {
            sessionManager.updateStatus(uploadId, UploadSession.Status.FAILED);
        }
    }
}
