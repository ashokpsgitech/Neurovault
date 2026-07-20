package com.neurovault.backend.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the storage module.
 * Bound to the {@code neurovault.storage} prefix in application.yml.
 */
@ConfigurationProperties(prefix = "neurovault.storage")
public class StorageProperties {

    /**
     * Base directory where host storage containers are created.
     * Each host gets a subdirectory named by its UUID.
     * Default: ./neurovault-storage
     */
    private String baseDir = "./neurovault-storage";

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }
}
