package com.neurovault.backend.storage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class that enables storage-related configuration properties.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {
    // Enables @ConfigurationProperties for StorageProperties
}
