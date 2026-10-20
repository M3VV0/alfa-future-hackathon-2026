package ru.alfa.credit.core;

import static ru.alfa.credit.core.Application.State.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.alfa.credit.contracts.*;
import ru.alfa.credit.platform.*;

@Service
public class CreditWorkflow implements JobHandler {
  private final Applications apps;
  private final JdbcTemplate db;
  private final Json json;
  private final Jobs jobs;
  private final Documents documents;
  private final DecisionPolicy policy;
  private final RestClient mocks, assessment;
  private final String model;
  private final jakarta.validation.Validator validator;

  public CreditWorkflow(
      Applications apps,
      JdbcTemplate db,
      Json json,
      Jobs jobs,
      Documents documents,
      DecisionPolicy policy,
      RestClient.Builder http,
      jakarta.validation.Validator validator,
      @Value("${integrations.mocks-url}") String mocksUrl,
      @Value("${integrations.assessment-url}") String assessmentUrl,
      @Value("${security.service-token}") String token,
      @Value("${scoring.model-version}") String model,
      @Value("${scoring.provider}") String provider) {
    if (!provider.equals("rules"))
      throw new IllegalArgumentException("Only rules scorer is supported");
    this.apps = apps;
    this.db = db;
    this.json = json;
    this.jobs = jobs;
    this.documents = documents;
    this.policy = policy;
    this.model = model;
    this.validator = validator;
    mocks = http.clone().baseUrl(mocksUrl).build();
    assessment =
        http.clone().baseUrl(assessmentUrl).defaultHeader("X-Service-Token", token).build();
  }

  @Override
  public void handle(String kind, JsonNode payload) {
    if (kind.equals("CLEAN_DOCUMENT")) {
      documents.cleanup(UUID.fromString(payload.path("documentId").asText()));
      return;
    }
    Application app = apps.load(UUID.fromString(payload.path("applicationId").asText()));
    if (app.currentRevision != payload.path("revision").asInt()) return;
    switch (kind) {
      case "VALIDATE" -> validate(app);
      case "ENRICH" -> enrich(app);
      case "DISPATCH" -> dispatch(app);
      case "RECONCILE" -> reconcile(app, payload.path("poll").asInt());
      case "DECIDE" -> decide(app);
      case "EXPIRE" -> expire(app);
      default -> throw new IllegalArgumentException("Unknown job kind");
    }
  }

  private JsonNode snapshot(Application app) {
    return json.read(
        db.queryForObject(
            "select snapshot::text from core.application_revision where application_id=? and"
                + " revision=?",
            String.class,
            app.id,
            app.currentRevision));
  }

  private void validate(Application app) {
    if (app.status != SUBMITTED) return;
    app.processingState = "RUNNING";
    apps.transition(app, VALIDATING, "VALIDATION_STARTED", "WORKER", "credit");
    JsonNode input = snapshot(app), draft = input.path("draft");
    List<String> missing = new ArrayList<>();
    for (String key : List.of("revenueMinor", "expensesMinor", "debtMinor", "period"))
      if (draft.path(key).isNull() || draft.path(key).isMissingNode()) missing.add(key);
    var reports =
        db.queryForList(
            "select d.* from core.revision_document r join core.document d on d.id=r.document_id"
                + " where r.application_id=? and r.revision=? and d.kind='FINANCIAL_REPORT' and"
                + " d.state='READY'",
            app.id,
            app.currentRevision);
    if (reports.isEmpty()) missing.add("FINANCIAL_REPORT");
    for (var report : reports) {
      JsonNode data = json.read(report.get("metadata").toString());
      if (!data.path("inn").asText().equals(input.path("company").path("inn").asText()))
        missing.add("DOCUMENT_INN_MISMATCH");
      for (String key : List.of("period", "revenueMinor", "expensesMinor"))
        if (!data.path(key).equals(draft.path(key))) missing.add("DOCUMENT_MISMATCH_" + key);
    }
    db.update(
        "insert into core.check_result(id,application_id,revision,outcome,reasons) values"
            + " (?,?,?,?,?::jsonb)",
        UUID.randomUUID(),
        app.id,
        app.currentRevision,
        missing.isEmpty() ? "VALID" : "MISSING_OR_INCONSISTENT",
        json.write(missing));
    if (!missing.isEmpty()) {
      needsInfo(app, missing);
      return;
    }
    apps.transition(app, ENRICHING, "VALIDATION_PASSED", "WORKER", "credit");
    jobs.enqueue("ENRICH", app.id + ":" + app.currentRevision + ":enrich", apps.payload(app));
  }

  private void needsInfo(Application app, List<String> fields) {
    db.update(
        "insert into core.info_request(id,application_id,revision,fields) values (?,?,?,?::jsonb)",
        UUID.randomUUID(),
        app.id,
        app.currentRevision,
        json.write(fields));
    apps.transition(app, NEEDS_INFO, "ADDITIONAL_INFORMATION_REQUIRED", "WORKER", "credit");
    app.processingState = "IDLE";
  }

  private void enrich(Application app) {
    if (app.status != ENRICHING) return;
    app.processingState = "RUNNING";
    JsonNode frozen = snapshot(app);
    String inn = frozen.path("company").path("inn").asText();
    Map<String, JsonNode> sources = new TreeMap<>();
    for (String source : List.of("registry", "tax", "bki")) {
      JsonNode response =
          mocks.get().uri("/" + source + "/{inn}", inn).retrieve().body(JsonNode.class);
      if (response == null
          || !response.path("found").isBoolean()
          || !response.path("fixtureVersion").isTextual())
        throw new IllegalStateException("INVALID_EXTERNAL_RESPONSE");
      if (response.path("found").asBoolean()) {
        if (source.equals("registry")
            && (!response.path("active").isBoolean() || !response.path("registeredAt").isTextual()))
          throw new IllegalStateException("INVALID_REGISTRY");
        if (source.equals("bki")
            && (!response.path("monthlyPaymentMinor").isIntegralNumber()
                || response.path("monthlyPaymentMinor").asLong() < 0
                || !response.path("maxOverdueDays").isIntegralNumber()
                || response.path("maxOverdueDays").asInt() < 0))
          throw new IllegalStateException("INVALID_BKI");
        if (source.equals("tax") && !response.path("discrepancy").isBoolean())
          throw new IllegalStateException("INVALID_TAX");
      }
      sources.put(source, response);
      db.update(
          "insert into"
              + " core.external_snapshot(application_id,revision,source,fixture_version,response_hash,response)"
              + " values (?,?,?,?,?,?::jsonb)",
          app.id,
          app.currentRevision,
          source,
          response.path("fixtureVersion").asText(),
          json.hash(response),
          json.write(response));
    }
    if (!sources.get("tax").path("found").asBoolean()
        || !sources.get("bki").path("found").asBoolean()
        || sources.get("tax").path("discrepancy").asBoolean()) {
      needsInfo(app, List.of("EXTERNAL_DATA_CLARIFICATION"));
      return;
    }
    ObjectNode input = (ObjectNode) frozen.deepCopy();
    input.set("sources", json.tree(sources));
    UUID request = UUID.randomUUID();
    String hash =
        json.hash(
            Map.of(
                "input", input, "provider", "rules", "model_version", model, "schema_version", 1));
    AssessmentRequest body =
        new AssessmentRequest(
            request,
            app.id,
            app.currentRevision,
            hash,
            1,
            "rules",
            model,
            input,
            TraceFilter.current());
    db.update(
        "insert into"
            + " core.assessment_request(request_id,application_id,revision,input_hash,provider,model_version,payload)"
            + " values (?,?,?,?,?,?,?::jsonb)",
        request,
        app.id,
        app.currentRevision,
        hash,
        "rules",
        model,
        json.write(body));
    apps.transition(app, SCORING, "ASSESSMENT_REQUESTED", "WORKER", "credit");
    jobs.enqueue("DISPATCH", request + ":dispatch", apps.payload(app));
    jobs.enqueueAt(
        "RECONCILE",
        request + ":reconcile:0",
        Map.of("applicationId", app.id, "revision", app.currentRevision, "poll", 0),
        Instant.now().plusSeconds(15));
  }

  private AssessmentRequest request(Application app) {
    return json.parse(
        db.queryForObject(
            "select payload::text from core.assessment_request where application_id=? and"
                + " revision=?",
            String.class,
            app.id,
            app.currentRevision),
        AssessmentRequest.class);
  }

  private void dispatch(Application app) {
    if (app.status != SCORING) return;
    app.processingState = "RUNNING";
    AssessmentRequest body = request(app);
    assessment.post().uri("/request").body(body).retrieve().toBodilessEntity();
  }

  private void reconcile(Application app, int poll) {
    if (app.status != SCORING) return;
    app.processingState = "RUNNING";
    AssessmentRequest request = request(app);
    JsonNode response =
        assessment.get().uri("/requests/{id}", request.requestId()).retrieve().body(JsonNode.class);
    if (response == null) throw new IllegalStateException("ASSESSMENT_UNAVAILABLE");
    if (response.path("status").asText().equals("SUCCEEDED")) {
      accept(json.parse(response.path("result").toString(), AssessmentResult.class));
      return;
    }
    if (response.path("status").asText().equals("FAILED"))
      throw new IllegalStateException("ASSESSMENT_FAILED");
    if (poll >= 60) throw new IllegalStateException("ASSESSMENT_TIMEOUT");
    jobs.enqueueAt(
        "RECONCILE",
        request.requestId() + ":reconcile:" + (poll + 1),
        Map.of("applicationId", app.id, "revision", app.currentRevision, "poll", poll + 1),
        Instant.now().plusSeconds(5));
  }

  // Caller must open a transaction. Callback and reconciliation use the same inbox path.
  public String accept(AssessmentResult result) {
    if (!validator.validate(result).isEmpty() || !result.consistent())
      throw Problem.invalid("INCONSISTENT_ASSESSMENT");
    db.queryForList(
        "select pg_advisory_xact_lock(hashtextextended(?,0))", result.eventId().toString());
    String hash = json.hash(result);
    var inbox =
        db.queryForList("select * from core.inbox_event where event_id=?", result.eventId());
    if (!inbox.isEmpty()) {
      if (!hash.equals(inbox.getFirst().get("payload_hash")))
        throw Problem.conflict("EVENT_PAYLOAD_MISMATCH");
      return inbox.getFirst().get("disposition").toString();
    }
    var requests =
        db.queryForList(
            "select * from core.assessment_request where request_id=?", result.requestId());
    if (requests.isEmpty()) throw Problem.missing();
    var expected = requests.getFirst();
    if (!result.applicationId().equals(expected.get("application_id"))
        || result.revision() != ((Number) expected.get("revision")).intValue()
        || !result.inputHash().equals(expected.get("input_hash"))
        || !result.provider().equals(expected.get("provider"))
        || !result.modelVersion().equals(expected.get("model_version")))
      throw Problem.conflict("ASSESSMENT_CORRELATION_MISMATCH");
    Application app = apps.load(result.applicationId());
    String disposition =
        (app.currentRevision != result.revision() || app.status != SCORING)
            ? "IGNORED"
            : "ACCEPTED";
    db.update(
        "insert into core.inbox_event(event_id,payload_hash,disposition) values (?,?,?)",
        result.eventId(),
        hash,
        disposition);
    if (disposition.equals("IGNORED")) {
      db.update(
          "insert into core.audit_event(actor,action,resource_id,trace_id) values"
              + " ('assessment','CALLBACK_IGNORED',?,?)",
          app.id,
          TraceFilter.current());
      return disposition;
    }
    db.update(
        "insert into core.assessment(id,request_id,application_id,revision,result) values"
            + " (?,?,?,?,?::jsonb)",
        UUID.randomUUID(),
        result.requestId(),
        app.id,
        app.currentRevision,
        json.write(result));
    apps.transition(app, DECIDING, "ASSESSMENT_RECEIVED", "SERVICE", "assessment");
    app.processingState = "RUNNING";
    jobs.enqueue("DECIDE", result.requestId() + ":decide", apps.payload(app));
    return disposition;
  }

  private void decide(Application app) {
    if (app.status != DECIDING) return;
    app.processingState = "RUNNING";
    var assessmentRow =
        db.queryForMap(
            "select * from core.assessment where application_id=? and revision=?",
            app.id,
            app.currentRevision);
    AssessmentResult assessment =
        json.parse(assessmentRow.get("result").toString(), AssessmentResult.class);
    Map<String, JsonNode> sources = new HashMap<>();
    db.queryForList(
            "select source,response from core.external_snapshot where application_id=? and"
                + " revision=?",
            app.id,
            app.currentRevision)
        .forEach(
            row ->
                sources.put(
                    row.get("source").toString(), json.read(row.get("response").toString())));
    JsonNode input = snapshot(app);
    var outcome = policy.decide(input, sources, assessment);
    db.update(
        "insert into"
            + " core.decision(id,application_id,revision,assessment_id,rule_version,product_version,outcome,reasons)"
            + " values (?,?,?,?,?,?,?,?::jsonb)",
        UUID.randomUUID(),
        app.id,
        app.currentRevision,
        assessmentRow.get("id"),
        DecisionPolicy.VERSION,
        app.productVersion,
        outcome.status(),
        json.write(outcome.reasons()));
    if (outcome.status().equals("OFFERED")) {
      UUID offer = UUID.randomUUID();
      Instant expiry = Instant.now().plus(Duration.ofDays(7));
      db.update(
          "insert into"
              + " core.offer(id,application_id,revision,amount_minor,term_months,annual_rate,monthly_payment_minor,counteroffer,expires_at)"
              + " values (?,?,?,?,?,?,?,?,?)",
          offer,
          app.id,
          app.currentRevision,
          outcome.amountMinor(),
          input.path("draft").path("termMonths").asInt(),
          input.path("product").path("annual_rate").decimalValue(),
          outcome.paymentMinor(),
          outcome.counteroffer(),
          java.sql.Timestamp.from(expiry));
      jobs.enqueueAt("EXPIRE", offer + ":expire", apps.payload(app), expiry);
    }
    apps.transition(
        app,
        Application.State.valueOf(outcome.status()),
        outcome.reasons().getFirst(),
        "WORKER",
        "credit");
    app.processingState = "IDLE";
  }

  private void expire(Application app) {
    if (app.status == OFFERED) {
      int changed =
          db.update(
              "update core.offer set status='EXPIRED' where application_id=? and status='OPEN' and"
                  + " expires_at<=now()",
              app.id);
      if (changed == 1) {
        apps.transition(app, EXPIRED, "OFFER_EXPIRED", "WORKER", "credit");
        app.processingState = "IDLE";
      }
    }
  }

  @Override
  public void retrying(String kind, JsonNode payload) {
    if (payload.has("applicationId")) {
      Application app = apps.load(UUID.fromString(payload.path("applicationId").asText()));
      if (app.currentRevision == payload.path("revision").asInt()
          && Set.of(SUBMITTED, VALIDATING, ENRICHING, SCORING, DECIDING).contains(app.status))
        app.processingState = "RETRY_WAIT";
    }
  }

  @Override
  public void failed(String kind, JsonNode payload) {
    if (payload.has("applicationId")) {
      Application app = apps.load(UUID.fromString(payload.path("applicationId").asText()));
      if (app.currentRevision == payload.path("revision").asInt()
          && !Set.of(
                  REJECTED,
                  NEEDS_INFO,
                  OFFERED,
                  ACCEPTED,
                  SIGNED_SIMULATED,
                  DISBURSED_SIMULATED,
                  CANCELLED,
                  EXPIRED,
                  DECLINED)
              .contains(app.status)) app.processingState = "FAILED";
    }
  }
}
