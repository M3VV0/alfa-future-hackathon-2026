package ru.alfa.credit.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Component;
import ru.alfa.credit.contracts.AssessmentResult;

@Component
public class DecisionPolicy {
  public static final String VERSION = "decision_v1";

  public record Outcome(
      String status,
      List<String> reasons,
      long amountMinor,
      long paymentMinor,
      boolean counteroffer) {}

  public Outcome decide(JsonNode snapshot, Map<String, JsonNode> sources, AssessmentResult score) {
    JsonNode draft = snapshot.path("draft"), product = snapshot.path("product");
    if (!sources.get("registry").path("found").asBoolean()
        || !sources.get("registry").path("active").asBoolean())
      return reject("BUSINESS_INACTIVE_OR_NOT_FOUND");
    if (sources.get("bki").path("maxOverdueDays").asInt() > 90) return reject("OVERDUE_GT_90");
    if (score.stopFlags().contains("SCORER_ALWAYS_REJECT")) return reject("SCORER_ALWAYS_REJECT");
    if (!score.stopFlags().isEmpty() || score.riskBand() == AssessmentResult.RiskBand.HIGH)
      return reject("HIGH_RISK");
    long age =
        ChronoUnit.MONTHS.between(
            LocalDate.parse(sources.get("registry").path("registeredAt").asText()),
            LocalDate.parse(snapshot.path("asOf").asText(LocalDate.now().toString())));
    if (age < product.path("min_age_months").asInt()) return reject("BUSINESS_TOO_YOUNG");
    long amount = draft.path("amountMinor").asLong();
    int term = draft.path("termMonths").asInt();
    if (amount < product.path("min_minor").asLong()
        || term < product.path("min_months").asInt()
        || term > product.path("max_months").asInt()) return reject("PRODUCT_LIMITS");
    BigDecimal net =
        BigDecimal.valueOf(draft.path("revenueMinor").asLong())
            .subtract(BigDecimal.valueOf(draft.path("expensesMinor").asLong()));
    if (net.signum() <= 0) return reject("NON_POSITIVE_INCOME");
    long debt =
        Math.max(
            draft.path("debtMinor").asLong(),
            sources.get("bki").path("monthlyPaymentMinor").asLong());
    BigDecimal available =
        net.multiply(product.path("burden_limit").decimalValue())
            .subtract(BigDecimal.valueOf(debt))
            .max(BigDecimal.ZERO);
    BigDecimal rate = product.path("annual_rate").decimalValue();
    long maxByPayment = available.divide(factor(rate, term), 0, RoundingMode.DOWN).longValueExact();
    long maxProduct = product.path("max_minor").asLong();
    if (score.riskBand() == AssessmentResult.RiskBand.MEDIUM) maxProduct /= 2;
    long cap = Math.min(maxByPayment, maxProduct);
    long offered = amount <= cap ? amount : (cap / 100_000) * 100_000;
    if (offered < product.path("min_minor").asLong()) return reject("INSUFFICIENT_CAPACITY");
    return new Outcome(
        "OFFERED",
        List.of(offered < amount ? "COUNTEROFFER" : "APPROVED"),
        offered,
        payment(offered, rate, term),
        offered < amount);
  }

  static BigDecimal factor(BigDecimal annual, int months) {
    BigDecimal r = annual.divide(BigDecimal.valueOf(12), MathContext.DECIMAL128);
    if (r.signum() == 0)
      return BigDecimal.ONE.divide(BigDecimal.valueOf(months), MathContext.DECIMAL128);
    BigDecimal divisor =
        BigDecimal.ONE.subtract(
            BigDecimal.ONE.divide(
                BigDecimal.ONE.add(r).pow(months, MathContext.DECIMAL128), MathContext.DECIMAL128));
    return r.divide(divisor, MathContext.DECIMAL128);
  }

  public static long payment(long amount, BigDecimal annual, int months) {
    return BigDecimal.valueOf(amount)
        .multiply(factor(annual, months))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  private Outcome reject(String code) {
    return new Outcome("REJECTED", List.of(code), 0, 0, false);
  }
}
