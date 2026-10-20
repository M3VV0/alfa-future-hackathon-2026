package ru.alfa.credit.assessment;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import ru.alfa.credit.contracts.*;
import ru.alfa.credit.platform.Jobs;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "serviceToken")
public class AssessmentController {
  private final AssessmentService service;
  private final Jobs jobs;

  public AssessmentController(AssessmentService service, Jobs jobs) {
    this.service = service;
    this.jobs = jobs;
  }

  @PostMapping("/request")
  public ResponseEntity<?> request(@RequestBody @Valid AssessmentRequest request) {
    return ResponseEntity.accepted().body(service.submit(request));
  }

  @GetMapping("/requests/{id}")
  public Map<String, Object> status(@PathVariable UUID id) {
    return service.status(id);
  }

  @PostMapping("/internal/jobs/{id}/replay")
  @Transactional
  public Map<String, String> replay(@PathVariable UUID id) {
    jobs.replay(id);
    return Map.of("status", "SCHEDULED");
  }
}
