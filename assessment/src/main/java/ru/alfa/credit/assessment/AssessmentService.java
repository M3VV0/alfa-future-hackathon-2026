package ru.alfa.credit.assessment;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.alfa.credit.contracts.*;
import ru.alfa.credit.platform.*;

@Service
@Transactional
public class AssessmentService {
  private final JdbcTemplate db;
  private final Json json;
  private final Jobs jobs;

  public AssessmentService(JdbcTemplate db, Json json, Jobs jobs) {
    this.db = db;
    this.json = json;
    this.jobs = jobs;
  }

  public Map<String, Object> submit(AssessmentRequest request) {
    if (!request.provider().equals("rules")
        || !request.modelVersion().equals(AlwaysRejectScorer.MODEL))
      throw Problem.invalid("UNSUPPORTED_SCORER");
    String inputHash =
        json.hash(
            Map.of(
                "input",
                request.input(),
                "provider",
                request.provider(),
                "model_version",
                request.modelVersion(),
                "schema_version",
                request.schemaVersion()));
    if (!inputHash.equals(request.inputHash())) throw Problem.conflict("INPUT_HASH_MISMATCH");
    db.queryForList(
        "select pg_advisory_xact_lock(hashtextextended(?,0))", request.requestId().toString());
    String payloadHash = json.hash(request);
    var previous =
        db.queryForList("select * from assessment.request where request_id=?", request.requestId());
    if (!previous.isEmpty()) {
      if (!payloadHash.equals(previous.getFirst().get("payload_hash")))
        throw Problem.conflict("REQUEST_PAYLOAD_MISMATCH");
      return status(request.requestId());
    }
    db.update(
        "insert into assessment.request(request_id,payload_hash,payload,status) values"
            + " (?,?,?::jsonb,'PENDING')",
        request.requestId(),
        payloadHash,
        json.write(request));
    jobs.enqueue("SCORE", request.requestId() + ":score", Map.of("requestId", request.requestId()));
    return status(request.requestId());
  }

  public Map<String, Object> status(UUID id) {
    var rows = db.queryForList("select * from assessment.request where request_id=?", id);
    if (rows.isEmpty()) throw Problem.missing();
    var row = rows.getFirst();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("request_id", id);
    response.put("status", row.get("status"));
    response.put("callback_state", row.get("callback_state"));
    response.put(
        "result", row.get("result") == null ? null : json.read(row.get("result").toString()));
    return response;
  }
}
