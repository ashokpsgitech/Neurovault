package com.neurovault.backend.controller;

import com.neurovault.backend.dto.*;
import com.neurovault.backend.download.DownloadService;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.repository.UserRepository;
import com.neurovault.backend.upload.UploadService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Control Plane REST Controller exposing Metadata Coordination endpoints for upload and download planning.
 *
 * <p>Strict Metadata-Only Architecture:
 * The Coordinator handles metadata, host planning, and session verification.
 * File bytes, encryption streams, and private keys NEVER pass through this controller.</p>
 */
@RestController
@RequestMapping("/api/files")
@Slf4j
public class FileController {

    private final UploadService uploadService;
    private final DownloadService downloadService;
    private final UserRepository userRepository;

    public FileController(
            UploadService uploadService,
            DownloadService downloadService,
            UserRepository userRepository) {
        this.uploadService = uploadService;
        this.downloadService = downloadService;
        this.userRepository = userRepository;
    }

    /**
     * Requests an upload plan from the Coordinator.
     * Returns assigned target host node endpoints and signed chunk authorization tokens.
     *
     * @param request metadata request from client
     * @return upload plan response
     */
    @PostMapping("/upload-plan")
    public ResponseEntity<UploadPlanResponse> requestUploadPlan(@Valid @RequestBody UploadPlanRequest request) {
        User user = getAuthenticatedUser();
        log.info("Upload plan request from user {} for file '{}'", user.getId(), request.getFilename());

        UploadPlanResponse plan = uploadService.createUploadPlan(request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(plan);
    }

    /**
     * Finalizes upload metadata after the client completes direct chunk uploads to host nodes.
     *
     * @param request completion request containing encrypted AES key and chunk hashes
     * @return upload completion response
     */
    @PostMapping("/upload-complete")
    public ResponseEntity<UploadResponse> completeUpload(@Valid @RequestBody UploadCompleteRequest request) {
        User user = getAuthenticatedUser();
        log.info("Upload completion request from user {} for session {}", user.getId(), request.getUploadSessionId());

        UploadResponse response = uploadService.completeUpload(request, user);
        return ResponseEntity.ok(response);
    }

    /**
     * Requests a download plan from the Coordinator.
     * Returns host chunk locations, chunk hashes, signed download tokens, and the encrypted AES key envelope.
     *
     * @param fileId the metadata file ID
     * @return download plan response
     */
    @PostMapping("/download-plan/{fileId}")
    public ResponseEntity<DownloadPlanResponse> requestDownloadPlan(@PathVariable UUID fileId) {
        User user = getAuthenticatedUser();
        log.info("Download plan request from user {} for file {}", user.getId(), fileId);

        DownloadPlanResponse plan = downloadService.createDownloadPlan(fileId, user);
        return ResponseEntity.ok(plan);
    }

    /**
     * Returns progress for an upload session.
     */
    @GetMapping("/progress/{uploadId}")
    public ResponseEntity<UploadProgressResponse> getUploadProgress(@PathVariable UUID uploadId) {
        UploadProgressResponse progress = uploadService.getProgress(uploadId);
        return ResponseEntity.ok(progress);
    }

    /**
     * Cancels an upload session.
     */
    @DeleteMapping("/cancel/{uploadId}")
    public ResponseEntity<Void> cancelUpload(@PathVariable UUID uploadId) {
        User user = getAuthenticatedUser();
        log.info("Cancel request from user {} for upload {}", user.getId(), uploadId);

        uploadService.cancelUpload(uploadId);
        return ResponseEntity.ok().build();
    }

    /**
     * Resolves the authenticated user from the Security Context.
     */
    private User getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
    }
}
