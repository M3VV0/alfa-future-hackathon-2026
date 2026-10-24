package dev.ammmmm.hackathon.smartcreditor.backend.controller;

import dev.ammmmm.hackathon.smartcreditor.backend.model.ScorerResult;
import dev.ammmmm.hackathon.smartcreditor.backend.service.CreditApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScorerCallbackController {
    private final CreditApplicationService applications;

    public ScorerCallbackController(CreditApplicationService applications) {
        this.applications = applications;
    }

    @PostMapping(value = "/scored", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> acceptResult(@Valid @RequestBody ScorerResult result) {
        applications.acceptScorerResult(result);
        return ResponseEntity.noContent().build();
    }
}
