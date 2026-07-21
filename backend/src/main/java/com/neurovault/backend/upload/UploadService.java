package com.neurovault.backend.upload;

import com.neurovault.backend.coordinator.CoordinatorService;
import com.neurovault.backend.dto.*;
import com.neurovault.backend.entity.*;
import com.neurovault.backend.exception.BadRequestException;
import com.neurovault.backend.exception.ResourceNotFoundException;
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
     * Selects online target hosts and issues signed chunk authorization tokens.
     *
     * @param request metadata request from client
     * @param user    authenticated user
     * @return upload plan containing host upload URLs and chunk tokens
     */
    @Transactional
    public UploadPlanResponse createUploadPlan(UploadPlanRequest request, User user) {
        log.info("Creating upload plan for file '{}' ({} bytes, {} chunks) for user {}",
                request.getFilename(), request.getFileSize(), request.getTotalChunks(), user.getId());

        // 1. Select active online hosts from host registry using weighted placement strategy
        List<Host> targetHosts = coordinatorService.selectTargetHosts(request.getTotalChunks());

        // 2. Create upload session
        UploadSession session = sessionManager.createSession(
                user, request.getFilename(), request.getFileSize(), request.getTotalChunks());

        UUID fileId = UUID.randomUUID();
        List<ChunkAllocationDto> allocations = new ArrayList<>();

        // 3. Allocate chunk indices across target hosts and issue chunk tokens
        for (int i = 0; i < request.getTotalChunks(); i++) {
            Host targetHost = targetHosts.get(i % targetHosts.size());
            String chunkToken = coordinatorService.generateChunkToken(session.getId(), targetHost.getId(), i);

            String uploadUrl = String.format("http://%s:8080/api/storage/chunk",
                    targetHost.getPublicIp() != null ? targetHost.getPublicIp() : "localhost");

            allocations.add(ChunkAllocationDto.builder()
                    .chunkIndex(i)
                    .hostId(targetHost.getId())
                    .hostName(targetHost.getName())
                    .publicIp(targetHost.getPublicIp())
                    .uploadUrl(uploadUrl)
                    .chunkToken(chunkToken)
                    .maxSizeBytes(4 * 1024 * 1024L) // 4MB maximum block size
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
     * Finalizes upload metadata once the client completes direct chunk streaming to host storage nodes.
     * Enforces replication factor assignment via ReplicationService.
     *
     * @param request completion payload containing encrypted AES key and chunk hashes
     * @param user    authenticated user
     * @return upload response confirmation
     */
    @Transactional
    public UploadResponse completeUpload(UploadCompleteRequest request, User user) {
        UploadSession session = sessionManager.getSession(request.getUploadSessionId());

        if (session.getStatus() == UploadSession.Status.COMPLETED) {
            throw new BadRequestException("Upload session is already completed");
        }

        log.info("Finalizing upload session {} for user {}", session.getId(), user.getId());

        // 1. Create and persist FileMetadata record
        FileMetadata fileMetadata = FileMetadata.builder()
                .owner(user)
                .name(session.getFileName())
                .path("/" + session.getFileName())
                .sizeBytes(session.getFileSize())
                .encryptedAesKey(request.getEncryptedAesKey())
                .fileHash("PENDING_HASH")
                .build();

        FileMetadata savedFile = fileMetadataRepository.save(fileMetadata);

        // 2. Create and persist Chunk and ChunkReplica metadata records via ReplicationService
        for (UploadCompleteRequest.UploadedChunkSummary chunkSummary : request.getUploadedChunks()) {
            Chunk chunkEntity = Chunk.builder()
                    .file(savedFile)
                    .chunkIndex(chunkSummary.getChunkIndex())
                    .sizeBytes(chunkSummary.getSizeBytes())
                    .checksum(chunkSummary.getChunkHash())
                    .status(Chunk.Status.ACTIVE)
                    .build();

            Chunk savedChunk = chunkRepository.save(chunkEntity);

            if (chunkSummary.getHostId() != null) {
                replicationService.assignReplicas(savedChunk.getId(), List.of(chunkSummary.getHostId()));
            }
        }

        // 3. Mark session COMPLETED
        sessionManager.updateStatus(session.getId(), UploadSession.Status.COMPLETED);

        log.info("Upload session {} successfully finalized: fileId={}", session.getId(), savedFile.getId());

        return UploadResponse.builder()
                .uploadId(session.getId())
                .fileId(savedFile.getId())
                .fileName(savedFile.getName())
                .fileSize(savedFile.getSizeBytes())
                .totalChunks(request.getUploadedChunks().size())
                .status(UploadSession.Status.COMPLETED.name())
                .createdAt(session.getCreatedAt())
                .encryptedAesKey(request.getEncryptedAesKey())
                .build();
    }

    /**
     * Returns progress metrics for an upload session.
     */
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

    /**
     * Cancels an upload session.
     */
    @Transactional
    public void cancelUpload(UUID uploadId) {
        UploadSession session = sessionManager.getSession(uploadId);

        if (session.getStatus() == UploadSession.Status.COMPLETED) {
            throw new BadRequestException("Cannot cancel a completed upload");
        }

        sessionManager.updateStatus(uploadId, UploadSession.Status.FAILED);
        log.info("Cancelled upload session {}", uploadId);
    }
}
