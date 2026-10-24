package dev.ammmmm.hackathon.smartcreditor.backend.controller;

import dev.ammmmm.hackathon.smartcreditor.backend.model.ScoredResult;
import dev.ammmmm.hackathon.smartcreditor.backend.service.CreditApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScoredController {
    private final CreditApplicationService applications;

    public ScoredController(CreditApplicationService applications) {
        this.applications = applications;
    }

    @PostMapping(value = "/scored", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> scored(@Valid @RequestBody ScoredResult result) {
        applications.acceptScored(result);
        return ResponseEntity.noContent().build();
    }
}
