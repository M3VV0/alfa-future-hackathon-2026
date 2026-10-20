package ru.alfa.credit.core;

import jakarta.validation.constraints.*;
import java.util.*;

public record Draft(
    @Min(1) long amountMinor,
    @Min(1) @Max(120) int termMonths,
    @NotBlank @Size(max = 1000) String purpose,
    @PositiveOrZero Long revenueMinor,
    @PositiveOrZero Long expensesMinor,
    @PositiveOrZero Long debtMinor,
    @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
    @NotNull @Size(max = 20) List<@NotNull UUID> documentIds) {
  public Draft {
    documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
  }
}
