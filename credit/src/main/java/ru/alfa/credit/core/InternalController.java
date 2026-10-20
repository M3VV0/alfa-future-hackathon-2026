package ru.alfa.credit.core;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import ru.alfa.credit.contracts.*;
import ru.alfa.credit.platform.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "serviceToken")
public class InternalController {
  private final CreditWorkflow workflow;
  private final Jobs jobs;

  public InternalController(CreditWorkflow workflow, Jobs jobs) {
    this.workflow = workflow;
    this.jobs = jobs;
  }

  @PostMapping("/internal/processed")
  @Transactional
  public Map<String, String> processed(@RequestBody @Valid AssessmentResult result) {
    return Map.of("disposition", workflow.accept(result));
  }

  @PostMapping("/internal/jobs/{id}/replay")
  @Transactional
  public Map<String, String> replay(@PathVariable UUID id) {
    jobs.replay(id);
    return Map.of("status", "SCHEDULED");
  }
}
