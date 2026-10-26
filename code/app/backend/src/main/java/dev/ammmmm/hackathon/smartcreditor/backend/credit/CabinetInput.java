package dev.ammmmm.hackathon.smartcreditor.backend.credit;

import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CabinetInput(@NotBlank String productId, @NotNull @Valid CreditApplicationRequest application,
                           @Valid Financials financials) {
}
