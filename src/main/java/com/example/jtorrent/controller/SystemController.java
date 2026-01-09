package com.example.jtorrent.controller;

import com.example.jtorrent.service.TorrentSessionManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for system health checks and information.
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "System", description = "System health and information APIs")
public class SystemController {

    private final TorrentSessionManager sessionManager;

    /**
     * Health check endpoint.
     *
     * @return system health status
     */
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Check if the torrent service is running")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();

        boolean sessionRunning = sessionManager.isRunning();

        health.put("status", sessionRunning ? "UP" : "DOWN");
        health.put("timestamp", LocalDateTime.now());
        health.put("sessionRunning", sessionRunning);
        health.put("service", "JTorrent");
        health.put("version", "1.0.0");

        if (sessionRunning) {
            return ResponseEntity.ok(health);
        } else {
            return ResponseEntity.status(503).body(health);
        }
    }

    /**
     * Get system information.
     *
     * @return system information
     */
    @GetMapping("/info")
    @Operation(summary = "System info", description = "Get torrent system information")
    public ResponseEntity<Map<String, Object>> getSystemInfo() {
        Map<String, Object> info = new HashMap<>();

        info.put("service", "JTorrent");
        info.put("version", "1.0.0");
        info.put("sessionRunning", sessionManager.isRunning());
        info.put("timestamp", LocalDateTime.now());

        // Add session info if running
        if (sessionManager.isRunning()) {
            info.put("activeTorrents", sessionManager.getActiveTorrents().size());
            info.put("downloadDirectory", sessionManager.getDownloadDirectory().getAbsolutePath());
        }

        return ResponseEntity.ok(info);
    }

    /**
     * Ping endpoint for connectivity check.
     *
     * @return pong message
     */
    @GetMapping("/ping")
    @Operation(summary = "Ping", description = "Simple connectivity check")
    public ResponseEntity<Map<String, String>> ping() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "pong");
        response.put("timestamp", LocalDateTime.now().toString());

        return ResponseEntity.ok(response);
    }
}
