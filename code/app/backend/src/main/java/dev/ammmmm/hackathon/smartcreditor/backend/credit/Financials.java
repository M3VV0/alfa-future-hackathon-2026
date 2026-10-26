package dev.ammmmm.hackathon.smartcreditor.backend.credit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** RUB/month. Unknown financials are absent, never silently replaced with zero. */
public record Financials(
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 2) BigDecimal monthlyRevenue,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 2) BigDecimal monthlyExpenses,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 2) BigDecimal existingMonthlyPayment) {
    public boolean sameValues(Financials other) {
        return other != null && monthlyRevenue.compareTo(other.monthlyRevenue) == 0
                && monthlyExpenses.compareTo(other.monthlyExpenses) == 0
                && existingMonthlyPayment.compareTo(other.existingMonthlyPayment) == 0;
    }
}
