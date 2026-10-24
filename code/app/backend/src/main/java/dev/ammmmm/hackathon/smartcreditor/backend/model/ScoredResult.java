package dev.ammmmm.hackathon.smartcreditor.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record ScoredResult(
        @NotNull Status status,
        @PositiveOrZero Integer months,
        @DecimalMin("0") @Digits(integer = 5, fraction = 4) BigDecimal percent,
        @DecimalMin("0") @Digits(integer = 15, fraction = 2) BigDecimal sum,
        @NotNull UUID id) {

    public enum Status { OK, DECLINED, OFFER }

    @JsonIgnore
    @AssertTrue(message = "OK and OFFER require positive months and sum, and a non-negative percent")
    public boolean isTermsValid() {
        return status == null || status == Status.DECLINED
                || (months != null && months > 0 && percent != null && percent.signum() >= 0
                && sum != null && sum.signum() > 0);
    }

    public boolean sameResult(ScoredResult other) {
        return status == other.status && Objects.equals(months, other.months) && Objects.equals(id, other.id)
                && sameDecimal(percent, other.percent) && sameDecimal(sum, other.sum);
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
}
