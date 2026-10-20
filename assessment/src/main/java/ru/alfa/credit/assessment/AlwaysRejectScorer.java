package ru.alfa.credit.assessment;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import ru.alfa.credit.contracts.*;
import ru.alfa.credit.contracts.AssessmentResult.*;

/** Temporary, deterministic implementation explicitly requested for the backend MVP. */
@Component
public class AlwaysRejectScorer implements RiskScorer {
  public static final String MODEL = "stub_reject_v1";

  @Override
  public AssessmentResult score(AssessmentRequest r) {
    return new AssessmentResult(
        UUID.nameUUIDFromBytes((r.requestId() + ":result:v1").getBytes(StandardCharsets.UTF_8)),
        r.requestId(),
        r.applicationId(),
        r.revision(),
        r.inputHash(),
        1,
        "rules",
        MODEL,
        105,
        RiskBand.HIGH,
        List.of(new Factor("TEMPORARY_ALWAYS_REJECT", 105)),
        List.of("SCORER_ALWAYS_REJECT"));
  }
}
