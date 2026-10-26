package dev.ammmmm.hackathon.smartcreditor.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

@Embeddable
public record CreditApplicationRequest(
        @NotNull
        @Column(nullable = false, updatable = false)
        UUID businessId,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 15, fraction = 2)
        @Column(nullable = false, precision = 17, scale = 2)
        BigDecimal requestedAmount,

        @NotNull
        @Positive
        @Column(nullable = false)
        Integer termMonths,

        @NotNull
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 16)
        PaymentFrequency paymentFrequency,

        @NotNull
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 32)
        FinancingPurpose financingPurpose,

        @Nullable
        @DecimalMin("0")
        @Digits(integer = 15, fraction = 2)
        @Column(precision = 17, scale = 2)
        BigDecimal declaredCollateralValue) {

    public enum PaymentFrequency {
        MONTHLY, QUARTERLY
    }

    public enum FinancingPurpose {
        WORKING_CAPITAL,
        EQUIPMENT,
        INVESTMENT,
        REFINANCING,
        BUSINESS_AUTO,
        COMMERCIAL_MORTGAGE
    }
}
