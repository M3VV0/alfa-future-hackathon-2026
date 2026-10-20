package ru.alfa.credit.contracts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

@com.fasterxml.jackson.databind.annotation.JsonNaming(
    com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AssessmentResult(
    @NotNull UUID eventId,
    @NotNull UUID requestId,
    @NotNull UUID applicationId,
    @Min(1) int revision,
    @Pattern(regexp = "[a-f0-9]{64}") @NotNull String inputHash,
    @Min(1) @Max(1) int schemaVersion,
    @NotBlank String provider,
    @NotBlank String modelVersion,
    @Min(0) @Max(105) int score,
    @NotNull RiskBand riskBand,
    @NotEmpty List<@Valid Factor> factors,
    @NotNull List<@NotBlank String> stopFlags) {
  public enum RiskBand {
    LOW,
    MEDIUM,
    HIGH
  }

  public record Factor(@NotBlank String code, @Min(0) @Max(105) int points) {}

  public boolean consistent() {
    return factors != null
        && factors.stream().mapToInt(Factor::points).sum() == score
        && riskBand == (score < 40 ? RiskBand.LOW : score < 80 ? RiskBand.MEDIUM : RiskBand.HIGH);
  }
}
