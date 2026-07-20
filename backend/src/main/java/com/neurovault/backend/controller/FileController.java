package com.neurovault.backend.controller;

import com.neurovault.backend.dto.DownloadResponse;
import com.neurovault.backend.dto.UploadProgressResponse;
import com.neurovault.backend.dto.UploadResponse;
import com.neurovault.backend.download.DownloadService;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.repository.UserRepository;
import com.neurovault.backend.upload.UploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * REST controller exposing the file upload, download, progress, retry, and cancel endpoints.
 *
 * <p>All endpoints require JWT authentication. The authenticated user is resolved
 * from the Security Context via their email (the JWT subject).</p>
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
     * Uploads a file. The file is encrypted, chunked, and persisted.
     *
     * @param file the multipart file to upload
     * @return the upload response with session and file metadata
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadFile(@RequestParam("file") MultipartFile file) {
        User user = getAuthenticatedUser();
        log.info("Upload request from user {} for file '{}'", user.getId(), file.getOriginalFilename());

        UploadResponse response = uploadService.initiateUpload(file, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Downloads a file by its file ID. Requires the RSA private key to unwrap the AES key.
     *
     * @param fileId     the file metadata ID
     * @param privateKey the Base64-encoded RSA private key (sent as a request header)
     * @return the decrypted original file as a binary download
     */
    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable UUID fileId,
            @RequestHeader("X-Private-Key") String privateKey) {

        User user = getAuthenticatedUser();
        log.info("Download request from user {} for file {}", user.getId(), fileId);

        Resource resource = downloadService.downloadFile(fileId, user, privateKey);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"download\"")
                .body(resource);
    }

    /**
     * Returns the current progress of an upload session.
     *
     * @param uploadId the upload session ID
     * @return the progress response
     */
    @GetMapping("/progress/{uploadId}")
    public ResponseEntity<UploadProgressResponse> getUploadProgress(@PathVariable UUID uploadId) {
        UploadProgressResponse progress = uploadService.getProgress(uploadId);
        return ResponseEntity.ok(progress);
    }

    /**
     * Retries failed chunk uploads for a session.
     *
     * @param uploadId the upload session ID
     * @return the updated upload response
     */
    @PostMapping("/retry/{uploadId}")
    public ResponseEntity<UploadResponse> retryUpload(@PathVariable UUID uploadId) {
        User user = getAuthenticatedUser();
        log.info("Retry request from user {} for upload {}", user.getId(), uploadId);

        UploadResponse response = uploadService.retryUpload(uploadId);
        return ResponseEntity.ok(response);
    }

    /**
     * Cancels an upload session.
     *
     * @param uploadId the upload session ID
     * @return 200 OK on success
     */
    @DeleteMapping("/cancel/{uploadId}")
    public ResponseEntity<Void> cancelUpload(@PathVariable UUID uploadId) {
        User user = getAuthenticatedUser();
        log.info("Cancel request from user {} for upload {}", user.getId(), uploadId);

        uploadService.cancelUpload(uploadId);
        return ResponseEntity.ok().build();
    }

    /**
     * Resolves the currently authenticated user from the Security Context.
     * The JWT subject is the user's email.
     *
     * @return the authenticated {@link User} entity
     * @throws ResourceNotFoundException if the user cannot be found
     */
    private User getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
    }
}
