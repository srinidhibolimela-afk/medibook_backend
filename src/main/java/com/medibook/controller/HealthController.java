package com.medibook.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health endpoint (/api/health) accessible publicly without authentication.
 * Returns overall application status, database connectivity, configured port, and timestamp.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final DataSource dataSource;

    @Value("${server.port:8081}")
    private int serverPort;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("application", "MediBook");
        body.put("port", serverPort);
        body.put("timestamp", Instant.now().toString());

        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(2);
            body.put("database", valid ? "UP" : "DOWN");
        } catch (Exception e) {
            body.put("database", "DOWN");
            body.put("databaseError", e.getMessage());
        }

        return ResponseEntity.ok(body);
    }
}
