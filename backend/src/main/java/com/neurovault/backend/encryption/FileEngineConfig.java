package com.neurovault.backend.encryption;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the NeuroVault file processing engine.
 *
 * <p>Properties are loaded from the {@code neurovault.engine} prefix in
 * {@code application.yml}. All values have sensible defaults.</p>
 *
 * <pre>
 * neurovault:
 *   engine:
 *     temp-dir: /tmp/neurovault
 *     chunk-size-bytes: 4194304
 *     max-retry-count: 3
 *     upload-session-ttl-hours: 1
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "neurovault.engine")
@Data
public class FileEngineConfig {

    /** Directory for temporary upload/download file storage. */
    private String tempDir = System.getProperty("java.io.tmpdir") + "/neurovault";

    /** Chunk size in bytes (default: 4 MB). */
    private int chunkSizeBytes = 4 * 1024 * 1024;

    /** Maximum number of retry attempts for failed chunk uploads/downloads. */
    private int maxRetryCount = 3;

    /** Upload session time-to-live in hours before expiration. */
    private int uploadSessionTtlHours = 1;
}
