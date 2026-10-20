package ru.alfa.credit.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.UUID;

@com.fasterxml.jackson.databind.annotation.JsonNaming(
    com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AssessmentRequest(
    @NotNull UUID requestId,
    @NotNull UUID applicationId,
    @Min(1) int revision,
    @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String inputHash,
    @Min(1) @Max(1) int schemaVersion,
    @NotBlank String provider,
    @NotBlank String modelVersion,
    @NotNull JsonNode input,
    @NotBlank String traceId) {}
