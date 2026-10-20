package ru.alfa.credit.core;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static ru.alfa.credit.core.DemoSeed.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.alfa.credit.contracts.*;
import ru.alfa.credit.contracts.AssessmentResult.*;
import ru.alfa.credit.platform.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(CreditIntegrationTest.StorageConfig.class)
class CreditIntegrationTest {
  static final String TOKEN = "test-service-token-with-at-least-32-characters";

  @Container
  static PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:17.6-alpine3.22").withInitScript("roles.sql");

  static final HttpServer external = server();
  static volatile boolean failMocks;

  static HttpServer server() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.startsWith("/request")) {
              body = "{\"status\":\"PENDING\"}";
              exchange.sendResponseHeaders(path.equals("/request") ? 202 : 200, body.length());
            } else if (failMocks) {
              body = "failure";
              exchange.sendResponseHeaders(503, body.length());
            } else {
              body =
                  path.startsWith("/registry/")
                      ? "{\"found\":true,\"active\":true,\"registeredAt\":\"2023-01-01\",\"fixtureVersion\":\"test_v1\"}"
                      : path.startsWith("/tax/")
                          ? "{\"found\":true,\"discrepancy\":false,\"fixtureVersion\":\"test_v1\"}"
                          : "{\"found\":true,\"monthlyPaymentMinor\":5000000,\"maxOverdueDays\":0,\"fixtureVersion\":\"test_v1\"}";
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, body.length());
            }
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
          });
      server.start();
      return server;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", () -> "credit");
    r.add("spring.datasource.password", () -> "test-credit-password");
    r.add("security.service-token", () -> TOKEN);
    r.add("storage.access-key", () -> "test");
    r.add("storage.secret-key", () -> "test");
    r.add("seed.enabled", () -> true);
    r.add("seed.password", () -> "test-demo-password");
    r.add("integrations.mocks-url", () -> "http://127.0.0.1:" + external.getAddress().getPort());
    r.add(
        "integrations.assessment-url", () -> "http://127.0.0.1:" + external.getAddress().getPort());
  }

  @TestConfiguration
  static class StorageConfig {
    @Bean
    @Primary
    DocumentStorage memoryStorage() {
      return new DocumentStorage() {
        final Map<String, byte[]> bytes = new ConcurrentHashMap<>();

        public void put(String key, byte[] data, String type) {
          bytes.put(key, data);
        }

        public byte[] get(String key) {
          return bytes.get(key);
        }

        public void delete(String key) {
          bytes.remove(key);
        }
      };
    }
  }

  @Autowired Applications apps;
  @Autowired Documents docs;
  @Autowired Jobs jobs;
  @Autowired JdbcTemplate db;
  @Autowired Json json;
  @Autowired MockMvc mvc;
  @Autowired TransactionTemplate tx;
  @Autowired EntityManager em;
  @Autowired DataSource ds;
  @Autowired Offers offers;
  @Autowired Profiles profiles;

  @BeforeEach
  void reset() {
    failMocks = false;
  }

  @AfterAll
  static void shutdown() {
    external.stop(0);
  }

  Draft draft(List<UUID> docs) {
    return new Draft(
        100000000,
        12,
        "Пополнение оборотных средств",
        100000000L,
        60000000L,
        5000000L,
        "2026-09",
        docs);
  }

  UUID report() {
    return docs.upload(
        ALICE,
        COMPANY_A,
        "FINANCIAL_REPORT",
        "report.json",
        "application/json",
        "{\"inn\":\"9900000017\",\"period\":\"2026-09\",\"revenueMinor\":100000000,\"expensesMinor\":60000000}"
            .getBytes(StandardCharsets.UTF_8),
        UUID.randomUUID());
  }

  UUID create(boolean report) {
    return apps.create(
        ALICE,
        COMPANY_A,
        "working-capital",
        draft(report ? List.of(report()) : List.of()),
        UUID.randomUUID());
  }

  long version(UUID id) {
    return ((Number) apps.detail(ALICE, id).get("version")).longValue();
  }

  String state(UUID id) {
    return apps.detail(ALICE, id).get("status").toString();
  }

  UUID job(UUID app, String kind) {
    return db.queryForObject(
        "select id from core.job where kind=? and payload->>'applicationId'=? order by created_at"
            + " desc limit 1",
        UUID.class,
        kind,
        app.toString());
  }

  void run(UUID app, String kind) {
    assertThat(jobs.execute(job(app, kind))).isNull();
  }

  void scoring(UUID id) {
    apps.submit(ALICE, id, version(id), UUID.randomUUID(), false);
    run(id, "VALIDATE");
    run(id, "ENRICH");
    run(id, "DISPATCH");
    assertThat(state(id)).isEqualTo("SCORING");
  }

  AssessmentResult result(UUID app, boolean reject) {
    var request =
        db.queryForMap(
            "select * from core.assessment_request where application_id=? order by revision desc"
                + " limit 1",
            app);
    return new AssessmentResult(
        UUID.randomUUID(),
        (UUID) request.get("request_id"),
        app,
        ((Number) request.get("revision")).intValue(),
        request.get("input_hash").toString(),
        1,
        "rules",
        "stub_reject_v1",
        reject ? 105 : 0,
        reject ? RiskBand.HIGH : RiskBand.LOW,
        List.of(new Factor("TEST", reject ? 105 : 0)),
        reject ? List.of("SCORER_ALWAYS_REJECT") : List.of());
  }

  void callback(AssessmentResult result) throws Exception {
    mvc.perform(
            post("/internal/processed")
                .header("X-Service-Token", TOKEN)
                .contentType("application/json")
                .content(json.write(result)))
        .andExpect(status().isOk());
  }

  @Test
  void fullRejectionAndDuplicateCallback() throws Exception {
    UUID id = create(true);
    scoring(id);
    AssessmentResult result = result(id, true);
    callback(result);
    callback(result);
    assertThat(state(id)).isEqualTo("DECIDING");
    run(id, "DECIDE");
    assertThat(state(id)).isEqualTo("REJECTED");
    run(id, "RECONCILE");
    assertThat(apps.detail(ALICE, id).get("processingState")).isEqualTo("IDLE");
    assertThat(
            db.queryForObject(
                "select reasons::text from core.decision where application_id=?", String.class, id))
        .contains("SCORER_ALWAYS_REJECT");
    assertThat(
            db.queryForObject(
                "select count(*) from core.assessment where application_id=?", Integer.class, id))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from core.decision where application_id=?", Integer.class, id))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from core.offer where application_id=?", Integer.class, id))
        .isZero();
    mvc.perform(
            post("/internal/processed")
                .header("X-Service-Token", TOKEN)
                .contentType("application/json")
                .content(
                    json.write(
                        new AssessmentResult(
                            result.eventId(),
                            result.requestId(),
                            id,
                            1,
                            result.inputHash(),
                            1,
                            "rules",
                            result.modelVersion(),
                            0,
                            RiskBand.LOW,
                            List.of(new Factor("TEST", 0)),
                            List.of()))))
        .andExpect(status().isConflict());
  }

  @Test
  void needsInfoResubmissionKeepsFrozenRevision() {
    UUID id = create(false);
    apps.submit(ALICE, id, version(id), UUID.randomUUID(), false);
    run(id, "VALIDATE");
    assertThat(state(id)).isEqualTo("NEEDS_INFO");
    String frozen =
        db.queryForObject(
            "select snapshot::text from core.application_revision where application_id=? and"
                + " revision=1",
            String.class,
            id);
    apps.save(ALICE, id, version(id), draft(List.of(report())), UUID.randomUUID());
    apps.submit(ALICE, id, version(id), UUID.randomUUID(), true);
    run(id, "VALIDATE");
    assertThat(state(id)).isEqualTo("ENRICHING");
    assertThat(apps.detail(ALICE, id).get("revision")).isEqualTo(2);
    assertThat(
            db.queryForObject(
                "select snapshot::text from core.application_revision where application_id=? and"
                    + " revision=1",
                String.class,
                id))
        .isEqualTo(frozen);
    assertThat(
            db.queryForObject(
                "select count(*) from core.application_revision where application_id=?",
                Integer.class,
                id))
        .isEqualTo(2);
  }

  @Test
  void submitIsIdempotentAndVersioned() {
    UUID id = create(true), command = UUID.randomUUID();
    long version = version(id);
    apps.submit(ALICE, id, version, command, false);
    apps.submit(ALICE, id, version, command, false);
    assertThat(
            db.queryForObject(
                "select count(*) from core.application_revision where application_id=?",
                Integer.class,
                id))
        .isEqualTo(1);
    assertThatThrownBy(() -> apps.submit(ALICE, id, version + 1, command, false))
        .isInstanceOf(Problem.class)
        .hasMessage("COMMAND_PAYLOAD_MISMATCH");
    UUID other = create(false);
    assertThatThrownBy(() -> apps.save(ALICE, other, 99, draft(List.of()), UUID.randomUUID()))
        .hasMessage("STALE_VERSION");
  }

  @Test
  void concurrentDuplicateCreateHasOneEffect() throws Exception {
    UUID command = UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var one =
          pool.submit(
              () -> apps.create(ALICE, COMPANY_A, "working-capital", draft(List.of()), command));
      var two =
          pool.submit(
              () -> apps.create(ALICE, COMPANY_A, "working-capital", draft(List.of()), command));
      assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(two.get(10, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void jpaAndJdbcSchedulerUseOneConnectionAndRollbackAtomically() {
    UUID id = create(true);
    int jobsBefore = db.queryForObject("select count(*) from core.scheduled_tasks", Integer.class);
    tx.executeWithoutResult(
        s -> {
          int jpa =
              ((Number) em.createNativeQuery("select pg_backend_pid()").getSingleResult())
                  .intValue();
          assertThat(db.queryForObject("select pg_backend_pid()", Integer.class)).isEqualTo(jpa);
          try (var c =
                  new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(ds)
                      .getConnection();
              var st = c.createStatement();
              var rs = st.executeQuery("select pg_backend_pid()")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(jpa);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          apps.submit(ALICE, id, version(id), UUID.randomUUID(), false);
          assertThat(db.queryForObject("select count(*) from core.scheduled_tasks", Integer.class))
              .isEqualTo(jobsBefore + 1);
          s.setRollbackOnly();
        });
    assertThat(state(id)).isEqualTo("DRAFT");
    assertThat(db.queryForObject("select count(*) from core.scheduled_tasks", Integer.class))
        .isEqualTo(jobsBefore);
    assertThat(
            db.queryForObject(
                "select count(*) from core.application_revision where application_id=?",
                Integer.class,
                id))
        .isZero();
    apps.submit(ALICE, id, version(id), UUID.randomUUID(), false);
    assertThat(db.queryForObject("select count(*) from core.scheduled_tasks", Integer.class))
        .isEqualTo(jobsBefore + 1);
  }

  @Test
  void exhaustedIntegrationRetriesAreTechnicalFailureAndReplayable() {
    UUID id = create(true);
    apps.submit(ALICE, id, version(id), UUID.randomUUID(), false);
    run(id, "VALIDATE");
    UUID job = job(id, "ENRICH");
    failMocks = true;
    for (int i = 0; i < 5; i++) assertThat(jobs.execute(job)).isNotNull();
    assertThat(jobs.execute(job)).isNull();
    assertThat(state(id)).isEqualTo("ENRICHING");
    assertThat(apps.detail(ALICE, id).get("processingState")).isEqualTo("FAILED");
    failMocks = false;
    tx.executeWithoutResult(s -> jobs.replay(job));
    run(id, "ENRICH");
    assertThat(state(id)).isEqualTo("SCORING");
  }

  @Test
  void ownershipCsrfAndInternalAuthentication() throws Exception {
    UUID id = create(true);
    UUID doc = report();
    assertThatThrownBy(() -> apps.detail(BOB, id)).hasMessage("NOT_FOUND");
    assertThatThrownBy(() -> docs.download(BOB, doc)).hasMessage("NOT_FOUND");
    mvc.perform(get("/app/companies/" + COMPANY_A).with(user("bob")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/app/applications/" + id).with(user("bob"))).andExpect(status().isNotFound());
    mvc.perform(get("/app/documents/" + doc + "/content").with(user("bob")))
        .andExpect(status().isNotFound());
    mvc.perform(post("/app/applications/" + id + "/submit").with(user("alice")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/internal/processed").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/app/applications").header("HX-Request", "true"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("HX-Redirect", "/login"));
    mvc.perform(
            post("/app/applications/" + id + "/submit")
                .with(user("alice"))
                .with(csrf())
                .param("version", Long.toString(version(id)))
                .param("command_id", UUID.randomUUID().toString()))
        .andExpect(status().isSeeOther());
  }

  @Test
  void filesValidateSignaturesAndChecksums() {
    assertThatThrownBy(
            () ->
                docs.upload(
                    ALICE,
                    COMPANY_A,
                    "OTHER",
                    "file.pdf",
                    "application/pdf",
                    "fake".getBytes(),
                    UUID.randomUUID()))
        .hasMessage("DOCUMENT_SIGNATURE");
    assertThatThrownBy(
            () ->
                docs.upload(
                    ALICE,
                    COMPANY_A,
                    "OTHER",
                    "../file.pdf",
                    "application/pdf",
                    "%PDF-1.4".getBytes(),
                    UUID.randomUUID()))
        .hasMessage("DOCUMENT_FILENAME");
    UUID id = report();
    assertThat(docs.download(ALICE, id).contentType()).isEqualTo("application/json");
  }

  @Test
  void offersAndSimulationsAreIdempotentWithAlternateTestAssessment() throws Exception {
    UUID id = create(true);
    scoring(id);
    callback(result(id, false));
    run(id, "DECIDE");
    assertThat(state(id)).isEqualTo("OFFERED");
    UUID offer =
        db.queryForObject("select id from core.offer where application_id=?", UUID.class, id);
    long version = version(id);
    UUID accept = UUID.randomUUID();
    offers.respond(ALICE, offer, version, accept, true);
    offers.respond(ALICE, offer, version, accept, true);
    UUID contract =
        db.queryForObject("select id from core.contract where application_id=?", UUID.class, id);
    assertThatThrownBy(() -> offers.contract(BOB, contract)).hasMessage("NOT_FOUND");
    assertThatThrownBy(() -> offers.offer(BOB, offer)).hasMessage("NOT_FOUND");
    offers.simulate(ALICE, contract, version(id), UUID.randomUUID(), false);
    long v = version(id);
    UUID command = UUID.randomUUID();
    offers.simulate(ALICE, contract, v, command, true);
    offers.simulate(ALICE, contract, v, command, true);
    assertThat(state(id)).isEqualTo("DISBURSED_SIMULATED");
    assertThat(
            db.queryForObject(
                "select count(*) from core.simulated_ledger_entry where contract_id=?",
                Integer.class,
                contract))
        .isEqualTo(1);
  }

  @Test
  void requestMismatchAndInvalidRiskAreRejected() throws Exception {
    UUID id = create(true);
    scoring(id);
    var result = result(id, true);
    mvc.perform(
            post("/internal/processed")
                .header("X-Service-Token", TOKEN)
                .contentType("application/json")
                .content(
                    json.write(
                        new AssessmentResult(
                            result.eventId(),
                            result.requestId(),
                            id,
                            1,
                            "0".repeat(64),
                            1,
                            "rules",
                            "stub_reject_v1",
                            105,
                            RiskBand.HIGH,
                            result.factors(),
                            result.stopFlags()))))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/internal/processed")
                .header("X-Service-Token", TOKEN)
                .contentType("application/json")
                .content(
                    json.write(
                        new AssessmentResult(
                            result.eventId(),
                            result.requestId(),
                            id,
                            1,
                            result.inputHash(),
                            1,
                            "rules",
                            "stub_reject_v1",
                            105,
                            RiskBand.LOW,
                            result.factors(),
                            result.stopFlags()))))
        .andExpect(status().isUnprocessableEntity());
    assertThat(state(id)).isEqualTo("SCORING");
  }

  @Test
  void profileUpdatesAreVersionedAndDoNotRewriteSnapshot() {
    var profile = profiles.user(ALICE);
    long version = ((Number) profile.get("version")).longValue();
    UUID command = UUID.randomUUID();
    profiles.updateUser(ALICE, version, "Демо-представитель", "ru", command);
    profiles.updateUser(ALICE, version, "Демо-представитель", "ru", command);
    assertThatThrownBy(
            () -> profiles.updateUser(ALICE, version, "Другое имя", "ru", UUID.randomUUID()))
        .hasMessage("STALE_VERSION");
  }

  @Test
  void exportedCallbackSchemaMatchesVersionedContract() throws Exception {
    var response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    JsonNode exported = json.read(response.getResponse().getContentAsString());
    JsonNode contract =
        json.read(
            new String(
                getClass().getResourceAsStream("/static/openapi/internal.yaml").readAllBytes(),
                StandardCharsets.UTF_8));
    Set<String> actual = new HashSet<>(), expected = new HashSet<>();
    exported
        .at("/components/schemas/AssessmentResult/properties")
        .fieldNames()
        .forEachRemaining(actual::add);
    contract
        .at("/components/schemas/AssessmentResult/properties")
        .fieldNames()
        .forEachRemaining(expected::add);
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void realLoginUsesCsrfAndJdbcSession() throws Exception {
    mvc.perform(get("/app/applications"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("http://localhost/login"));
    var login = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
    var token =
        (org.springframework.security.web.csrf.CsrfToken)
            login
                .getRequest()
                .getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
    var authenticated =
        mvc.perform(
                post("/login")
                    .cookie(login.getResponse().getCookies())
                    .param("username", "alice")
                    .param("password", "test-demo-password")
                    .param("_csrf", token.getToken()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/app/applications"))
            .andReturn();
    var session = authenticated.getResponse().getCookie("SESSION");
    assertThat(session).isNotNull();
    mvc.perform(get("/app/applications").cookie(session)).andExpect(status().isNotImplemented());
    assertThat(
            db.queryForObject(
                "select count(*) from auth.spring_session where principal_name='alice'",
                Integer.class))
        .isGreaterThan(0);
  }
}
