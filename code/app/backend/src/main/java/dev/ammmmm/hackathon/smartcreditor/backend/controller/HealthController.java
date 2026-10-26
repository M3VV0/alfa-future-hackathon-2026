package dev.ammmmm.hackathon.smartcreditor.backend.controller;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/health")
    public ResponseEntity<String> health() {
        try {
            return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class))
                    ? ResponseEntity.ok("OK") : ResponseEntity.status(503).body("UNAVAILABLE");
        } catch (DataAccessException error) {
            return ResponseEntity.status(503).body("UNAVAILABLE");
        }
    }
}
