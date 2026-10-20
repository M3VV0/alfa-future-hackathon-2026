package ru.alfa.credit.assessment;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.alfa.credit.contracts.*;
import ru.alfa.credit.platform.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AssessmentIntegrationTest {
  static final String TOKEN = "test-service-token-with-at-least-32-characters";

  @Container
  static PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:17.6-alpine3.22").withInitScript("roles.sql");

  static final List<String> callbacks = new java.util.concurrent.CopyOnWriteArrayList<>();
  static final HttpServer callbackServer = start();

  static HttpServer start() {
    try {
      var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext(
          "/internal/processed",
          e -> {
            callbacks.add(
                new String(
                    e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            e.sendResponseHeaders(200, -1);
            e.close();
          });
      s.start();
      return s;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", () -> "assessment");
    r.add("spring.datasource.password", () -> "test-assessment-password");
    r.add("security.service-token", () -> TOKEN);
    r.add(
        "integrations.credit-url",
        () -> "http://127.0.0.1:" + callbackServer.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    callbackServer.stop(0);
  }

  @Autowired AssessmentService service;
  @Autowired Jobs jobs;
  @Autowired JdbcTemplate db;
  @Autowired Json json;
  @Autowired MockMvc mvc;
  @Autowired TransactionTemplate tx;

  AssessmentRequest request() {
    JsonNode input = json.tree(Map.of("revenueMinor", 100000000));
    String hash =
        json.hash(
            Map.of(
                "input",
                input,
                "provider",
                "rules",
                "model_version",
                AlwaysRejectScorer.MODEL,
                "schema_version",
                1));
    return new AssessmentRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        hash,
        1,
        "rules",
        AlwaysRejectScorer.MODEL,
        input,
        "test");
  }

  UUID job(UUID request, String kind) {
    return db.queryForObject(
        "select id from assessment.job where kind=? and payload->>'requestId'=? order by created_at"
            + " desc limit 1",
        UUID.class,
        kind,
        request.toString());
  }

  @Test
  void durableRequestScoreCallbackAndReconciliationRead() throws Exception {
    var request = request();
    mvc.perform(
            post("/request")
                .header("X-Service-Token", TOKEN)
                .contentType("application/json")
                .content(json.write(request)))
        .andExpect(status().isAccepted());
    service.submit(request);
    assertThat(
            db.queryForObject(
                "select count(*) from assessment.job where payload->>'requestId'=?",
                Integer.class,
                request.requestId().toString()))
        .isEqualTo(1);
    assertThat(jobs.execute(job(request.requestId(), "SCORE"))).isNull();
    var result = (JsonNode) service.status(request.requestId()).get("result");
    assertThat(result.path("score").asInt()).isEqualTo(105);
    assertThat(result.path("stop_flags").get(0).asText()).isEqualTo("SCORER_ALWAYS_REJECT");
    assertThat(jobs.execute(job(request.requestId(), "CALLBACK"))).isNull();
    assertThat(callbacks)
        .anySatisfy(body -> assertThat(body).contains(request.requestId().toString()));
    mvc.perform(get("/requests/" + request.requestId()).header("X-Service-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.callback_state").value("DELIVERED"));
  }

  @Test
  void requestPayloadConflictAndHashValidation() {
    var request = request();
    service.submit(request);
    var changed =
        new AssessmentRequest(
            request.requestId(),
            UUID.randomUUID(),
            1,
            request.inputHash(),
            1,
            "rules",
            AlwaysRejectScorer.MODEL,
            request.input(),
            "test");
    assertThatThrownBy(() -> service.submit(changed)).hasMessage("REQUEST_PAYLOAD_MISMATCH");
    var invalid =
        new AssessmentRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "0".repeat(64),
            1,
            "rules",
            AlwaysRejectScorer.MODEL,
            request.input(),
            "test");
    assertThatThrownBy(() -> service.submit(invalid)).hasMessage("INPUT_HASH_MISMATCH");
  }

  @Test
  void queueAndRequestRollbackTogether() {
    var request = request();
    tx.executeWithoutResult(
        s -> {
          service.submit(request);
          s.setRollbackOnly();
        });
    assertThat(
            db.queryForObject(
                "select count(*) from assessment.request where request_id=?",
                Integer.class,
                request.requestId()))
        .isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from assessment.job where payload->>'requestId'=?",
                Integer.class,
                request.requestId().toString()))
        .isZero();
  }

  @Test
  void onlyServiceCredentialsAccepted() throws Exception {
    mvc.perform(post("/request").contentType("application/json").content(json.write(request())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.trace_id").isNotEmpty());
    mvc.perform(
            post("/request")
                .header("X-Service-Token", TOKEN)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnprocessableEntity());
  }
}
