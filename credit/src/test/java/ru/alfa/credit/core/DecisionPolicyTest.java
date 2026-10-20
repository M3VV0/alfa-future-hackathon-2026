package ru.alfa.credit.core;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.alfa.credit.contracts.*;
import ru.alfa.credit.contracts.AssessmentResult.*;

class DecisionPolicyTest {
  final ObjectMapper json = new ObjectMapper();

  @Test
  void annuityMatchesArchitecture() {
    assertThat(DecisionPolicy.payment(100000000, new BigDecimal("0.18"), 12))
        .isBetween(9167990L, 9168010L);
  }

  @Test
  void zeroRateAndRounding() {
    assertThat(DecisionPolicy.payment(120000, new BigDecimal("0"), 12)).isEqualTo(10000);
  }

  @Test
  void terminalStatesCannotBeReopened() {
    assertThat(StateTransitions.allowed(Application.State.REJECTED, Application.State.SUBMITTED))
        .isFalse();
    assertThat(StateTransitions.allowed(Application.State.NEEDS_INFO, Application.State.SUBMITTED))
        .isTrue();
  }

  @Test
  void counterOfferRoundsDownToThousandRubles() throws Exception {
    JsonNode input =
        json.readTree(
            "{\"draft\":{\"amountMinor\":300000000,\"termMonths\":24,\"revenueMinor\":100000000,\"expensesMinor\":60000000,\"debtMinor\":5000000},\"product\":{\"min_age_months\":6,\"min_minor\":10000000,\"max_minor\":300000000,\"min_months\":6,\"max_months\":24,\"annual_rate\":0.18,\"burden_limit\":0.45}}");
    var score =
        new AssessmentResult(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "a".repeat(64),
            1,
            "rules",
            "test",
            0,
            RiskBand.LOW,
            List.of(new Factor("TEST", 0)),
            List.of());
    var outcome =
        new DecisionPolicy()
            .decide(
                input,
                Map.of(
                    "registry",
                    json.readTree(
                        "{\"found\":true,\"active\":true,\"registeredAt\":\"2023-01-01\"}"),
                    "bki",
                    json.readTree("{\"monthlyPaymentMinor\":5000000,\"maxOverdueDays\":0}")),
                score);
    assertThat(outcome.status()).isEqualTo("OFFERED");
    assertThat(outcome.counteroffer()).isTrue();
    assertThat(outcome.amountMinor()).isEqualTo(260300000L);
  }
}
