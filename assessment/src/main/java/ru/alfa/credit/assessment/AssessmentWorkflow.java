package ru.alfa.credit.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.alfa.credit.contracts.*;
import ru.alfa.credit.platform.*;

@Service
public class AssessmentWorkflow implements JobHandler {
  private final JdbcTemplate db;
  private final Json json;
  private final Jobs jobs;
  private final RiskScorer scorer;
  private final RestClient credit;

  public AssessmentWorkflow(
      JdbcTemplate db,
      Json json,
      Jobs jobs,
      RiskScorer scorer,
      RestClient.Builder http,
      @Value("${integrations.credit-url}") String url,
      @Value("${security.service-token}") String token) {
    this.db = db;
    this.json = json;
    this.jobs = jobs;
    this.scorer = scorer;
    credit = http.clone().baseUrl(url).defaultHeader("X-Service-Token", token).build();
  }

  @Override
  public void handle(String kind, JsonNode payload) {
    UUID id = UUID.fromString(payload.path("requestId").asText());
    var row = db.queryForMap("select * from assessment.request where request_id=? for update", id);
    switch (kind) {
      case "SCORE" -> {
        if (row.get("status").equals("SUCCEEDED")) return;
        db.update(
            "update assessment.request set status='RUNNING',updated_at=now() where request_id=?",
            id);
        AssessmentResult result =
            scorer.score(json.parse(row.get("payload").toString(), AssessmentRequest.class));
        if (!result.consistent()) throw new IllegalStateException("INVALID_SCORER_RESULT");
        db.update(
            "update assessment.request set status='SUCCEEDED',result=?::jsonb,updated_at=now()"
                + " where request_id=?",
            json.write(result),
            id);
        jobs.enqueue("CALLBACK", id + ":callback", Map.of("requestId", id));
      }
      case "CALLBACK" -> {
        if ("DELIVERED".equals(row.get("callback_state"))) return;
        credit
            .post()
            .uri("/internal/processed")
            .body(json.read(row.get("result").toString()))
            .retrieve()
            .toBodilessEntity();
        db.update(
            "update assessment.request set callback_state='DELIVERED',updated_at=now() where"
                + " request_id=?",
            id);
      }
      default -> throw new IllegalArgumentException("Unknown assessment job");
    }
  }

  @Override
  public void failed(String kind, JsonNode payload) {
    if (kind.equals("SCORE"))
      db.update(
          "update assessment.request set status='FAILED',updated_at=now() where request_id=?",
          UUID.fromString(payload.path("requestId").asText()));
    else
      db.update(
          "update assessment.request set callback_state='FAILED',updated_at=now() where"
              + " request_id=?",
          UUID.fromString(payload.path("requestId").asText()));
  }
}
