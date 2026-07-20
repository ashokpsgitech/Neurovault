package com.neurovault.backend.host.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration class for the host module.
 * Enables Spring's scheduled task execution for the heartbeat monitor.
 */
@Configuration
@EnableScheduling
public class HostConfig {
    // Enables @Scheduled annotations used by HeartbeatScheduler
}
