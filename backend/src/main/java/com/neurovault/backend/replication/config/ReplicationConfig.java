package com.neurovault.backend.replication.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/**
 * Configuration properties for the NeuroVault replication subsystem.
 *
 * <p>All values are externalized via {@code application.yml} under the
 * {@code neurovault.replication} prefix and can be overridden per environment.</p>
 *
 * @author NeuroVault Team
 */
@Configuration
@ConfigurationProperties(prefix = "neurovault.replication")
@Validated
@Data
public class ReplicationConfig {

    /**
     * Target number of replicas per chunk.
     * A factor of 3 means every chunk is stored on 3 different hosts.
     */
    @Positive
    @Min(1)
    @Max(10)
    private int factor = 3;

    /**
     * Number of seconds after the last heartbeat before a host is considered timed-out.
     * Should be at least 2–3× the host heartbeat interval (default 30s).
     */
    @Positive
    private int heartbeatTimeoutSeconds = 90;

    /**
     * Percentage of total capacity below which a host is flagged as "low storage".
     * For example, 10 means the host is flagged when available capacity drops below 10%.
     */
    @Min(1)
    @Max(50)
    private int lowStorageThresholdPercent = 10;

    /**
     * Maximum number of repair operations that can be initiated in a single
     * maintenance cycle. Prevents overloading the cluster during mass recovery.
     */
    @Positive
    private int maxConcurrentRepairs = 5;

    /**
     * Interval in milliseconds between scheduler maintenance cycles.
     * Default 30000ms = 30 seconds.
     */
    @Positive
    private long schedulerIntervalMs = 30000;
}
