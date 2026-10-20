package ru.alfa.credit.assessment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.alfa.credit.contracts.*;

class AlwaysRejectScorerTest {
  @Test
  void alwaysRejectsAndResultIsDeterministic() {
    var scorer = new AlwaysRejectScorer();
    for (int i = 0; i < 50; i++) {
      var request =
          new AssessmentRequest(
              UUID.randomUUID(),
              UUID.randomUUID(),
              1,
              "a".repeat(64),
              1,
              "rules",
              AlwaysRejectScorer.MODEL,
              new ObjectMapper().valueToTree(Map.of("any", i)),
              "test");
      var score = scorer.score(request);
      assertThat(score).isEqualTo(scorer.score(request));
      assertThat(score.consistent()).isTrue();
      assertThat(score.riskBand()).isEqualTo(AssessmentResult.RiskBand.HIGH);
      assertThat(score.stopFlags()).containsExactly("SCORER_ALWAYS_REJECT");
    }
  }
}
