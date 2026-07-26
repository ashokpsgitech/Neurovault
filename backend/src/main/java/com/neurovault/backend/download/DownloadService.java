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
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${neurovault.host.port:8080}")
    private int hostPort;

    @Value("${neurovault.host.use-tls:false}")
    private boolean useTls;

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

        List<Chunk> chunks = chunkRepository.findByFileId(fileId);
        chunks.sort(Comparator.comparingInt(Chunk::getChunkIndex));

        DownloadSession session = sessionManager.createSession(user, file, chunks.size());

        List<ChunkLocationDto> chunkLocations = new ArrayList<>();
        String scheme = useTls ? "https" : "http";

        for (Chunk chunk : chunks) {
            List<ChunkReplica> replicas = chunkReplicaRepository.findByChunkId(chunk.getId());

            Host targetHost = replicas.stream()
                    .map(ChunkReplica::getHost)
                    .filter(h -> h != null && h.getStatus() == Host.Status.ONLINE)
                    .findFirst()
                    .orElse(null);

            boolean isAvailable = (targetHost != null);
            UUID hostId = null;
            String hostName = "Unavailable Host";
            String publicIp = "0.0.0.0";
            String downloadToken = null;
            String downloadUrl = null;

            if (isAvailable) {
                hostId = targetHost.getId();
                hostName = targetHost.getName() != null ? targetHost.getName() : "Host-Node";
                publicIp = targetHost.getPublicIp() != null ? targetHost.getPublicIp() : "localhost";
                downloadToken = coordinatorService.generateChunkToken(session.getId(), hostId, chunk.getChunkIndex());
                downloadUrl = String.format("%s://%s:%d/api/storage/chunks/%s", scheme, publicIp, hostPort, chunk.getId());
            } else {
                log.warn("No ONLINE host replica found for chunk {} (fileId={})", chunk.getId(), fileId);
            }

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
                    .available(isAvailable)
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

        int total = session.getTotalChunks() != null ? session.getTotalChunks() : 1;
        int completed = session.getCompletedChunks() != null ? session.getCompletedChunks() : 0;
        double pct = total > 0 ? (completed * 100.0 / total) : 0.0;

        return DownloadProgressResponse.builder()
                .downloadId(downloadId)
                .fileName(file.getName())
                .totalChunks(total)
                .completedChunks(completed)
                .progressPercent(pct)
                .status(session.getStatus().name())
                .build();
    }
}
