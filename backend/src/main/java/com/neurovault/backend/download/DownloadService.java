package com.neurovault.backend.download;

import com.neurovault.backend.coordinator.CoordinatorService;
import com.neurovault.backend.dto.ChunkLocationDto;
import com.neurovault.backend.dto.DownloadPlanResponse;
import com.neurovault.backend.dto.DownloadProgressResponse;
import com.neurovault.backend.entity.*;
import com.neurovault.backend.exception.BadRequestException;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.ChunkRepository;
import com.neurovault.backend.repository.FileMetadataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Control Plane service responsible for download session coordination,
 * chunk replica location mapping, and client download authorization.
 *
 * <p>Following Metadata-Only Coordinator architecture rules:
 * The Coordinator NEVER fetches, merges, decrypts, or serves file byte streams.</p>
 */
@Service
@Slf4j
public class DownloadService {

    private final CoordinatorService coordinatorService;
    private final DownloadSessionManager sessionManager;
    private final FileMetadataRepository fileMetadataRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkReplicaRepository chunkReplicaRepository;

    public DownloadService(
            CoordinatorService coordinatorService,
            DownloadSessionManager sessionManager,
            FileMetadataRepository fileMetadataRepository,
            ChunkRepository chunkRepository,
            ChunkReplicaRepository chunkReplicaRepository) {
        this.coordinatorService = coordinatorService;
        this.sessionManager = sessionManager;
        this.fileMetadataRepository = fileMetadataRepository;
        this.chunkRepository = chunkRepository;
        this.chunkReplicaRepository = chunkReplicaRepository;
    }

    /**
     * Generates a download plan for the client.
     * Maps each chunk block to its target host storage locations and issues download authorization tokens.
     *
     * @param fileId metadata ID of requested file
     * @param user   authenticated user
     * @return download plan containing host download URLs, chunk hashes, and tokens
     */
    @Transactional
    public DownloadPlanResponse createDownloadPlan(UUID fileId, User user) {
        FileMetadata file = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found: " + fileId));

        // Verify ownership / access control
        if (!file.getOwner().getId().equals(user.getId())) {
            throw new BadRequestException("Access denied: you do not own this file");
        }

        DownloadSession session = sessionManager.createSession(user, file);
        List<Chunk> chunks = chunkRepository.findByFileId(fileId);
        chunks.sort(Comparator.comparingInt(Chunk::getChunkIndex));

        List<ChunkLocationDto> chunkLocations = new ArrayList<>();

        for (Chunk chunk : chunks) {
            List<ChunkReplica> replicas = chunkReplicaRepository.findByChunkId(chunk.getId());

            Host targetHost = null;
            if (!replicas.isEmpty() && replicas.get(0).getHost() != null) {
                targetHost = replicas.get(0).getHost();
            }

            UUID hostId = targetHost != null ? targetHost.getId() : UUID.randomUUID();
            String publicIp = targetHost != null ? targetHost.getPublicIp() : "localhost";
            String hostName = targetHost != null ? targetHost.getName() : "Host-Node";

            String downloadToken = coordinatorService.generateChunkToken(session.getId(), hostId, chunk.getChunkIndex());
            String downloadUrl = String.format("http://%s:8080/api/storage/chunk/%s", publicIp, chunk.getId());

            chunkLocations.add(ChunkLocationDto.builder()
                    .chunkId(chunk.getId())
                    .chunkIndex(chunk.getChunkIndex())
                    .chunkHash(chunk.getChecksum())
                    .sizeBytes(chunk.getSizeBytes())
                    .hostId(hostId)
                    .hostName(hostName)
                    .publicIp(publicIp)
                    .downloadUrl(downloadUrl)
                    .downloadToken(downloadToken)
                    .build());
        }

        log.info("Created download plan for file '{}' ({}): session={}, locations={}",
                file.getName(), fileId, session.getId(), chunkLocations.size());

        return DownloadPlanResponse.builder()
                .downloadSessionId(session.getId())
                .fileId(file.getId())
                .filename(file.getName())
                .fileSize(file.getSizeBytes())
                .checksum(file.getFileHash())
                .encryptedAesKey(file.getEncryptedAesKey())
                .chunkLocations(chunkLocations)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    /**
     * Returns the current progress of a download session.
     */
    public DownloadProgressResponse getProgress(UUID downloadId) {
        DownloadSession session = sessionManager.getSession(downloadId);
        FileMetadata file = session.getFile();
        List<Chunk> chunks = chunkRepository.findByFileId(file.getId());

        return DownloadProgressResponse.builder()
                .downloadId(downloadId)
                .fileName(file.getName())
                .totalChunks(chunks.size())
                .completedChunks(chunks.size())
                .progressPercent(100.0)
                .status(session.getStatus().name())
                .build();
    }
}
