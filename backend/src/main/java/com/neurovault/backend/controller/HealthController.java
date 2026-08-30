package com.neurovault.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Public health check and status controller for Azure App Service probes and monitoring.
 */
@RestController
public class HealthController {

    @GetMapping({"/", "/health", "/api/health"})
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "NeuroVault 24/7 Cloud Metadata Coordinator",
            "mode", "Zero-Trust Distributed Storage Coordinator",
            "version", "2.0.0",
            "timestamp", Instant.now().toString()
        ));
    }
}
