package dev.ammmmm.hackathon.smartcreditor.backend;

import dev.ammmmm.hackathon.smartcreditor.backend.client.ScorerClient;
import dev.ammmmm.hackathon.smartcreditor.backend.credit.*;
import dev.ammmmm.hackathon.smartcreditor.backend.model.*;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.*;
import dev.ammmmm.hackathon.smartcreditor.backend.service.CreditApplicationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.demo-data.enabled=false", "credit.application.worker.enabled=true",
        "credit.application.worker.initial-delay-ms=3600000", "scorer.token=cabinet-test-token",
        "scorer.retry-delay=PT0S", "scorer.max-attempts=2"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CreditCabinetTests {
    private static final String SCHEMA = "cabinet_test_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @AfterAll static void cleanup(@Autowired JdbcTemplate jdbc) { jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE"); }
    @Autowired CreditCabinetService cabinet;
    @Autowired CreditApplicationService scoring;
    @Autowired UserRepository users;
    @Autowired BusinessRepository businesses;
    @Autowired BusinessAccessRepository memberships;
    @Autowired CreditApplicationRepository applications;
    @Autowired CreditApplicationTaskRepository tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean ScorerClient scorer;
    private UUID owner, viewer, other;
    private Business business;

    @BeforeEach void fixtures() {
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@cabinet.test", "hash")).getId();
        viewer = users.saveAndFlush(new User(UUID.randomUUID() + "@cabinet.test", "hash")).getId();
        other = users.saveAndFlush(new User(UUID.randomUUID() + "@cabinet.test", "hash")).getId();
        business = new Business(Business.Type.LEGAL_ENTITY, "77" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000000L)));
        business.setRegisteredName("Тестовая компания");
        business.setRegisteredDate(LocalDate.of(2020, 1, 1));
        business.setRegistrationStatus(Business.RegistrationStatus.ACTIVE);
        businesses.saveAndFlush(business);
        memberships.saveAndFlush(new BusinessAccess(owner, business.getId(), BusinessAccess.Role.OWNER));
        memberships.saveAndFlush(new BusinessAccess(viewer, business.getId(), BusinessAccess.Role.VIEWER));
    }
    @AfterEach void remove() {
        jdbc.update("DELETE FROM \"CreditApplication\" WHERE \"businessId\" = ?", business.getId());
        jdbc.update("DELETE FROM \"BusinessAccess\" WHERE \"businessId\" = ?", business.getId());
        businesses.deleteById(business.getId());
        users.deleteAllById(List.of(owner, viewer, other));
    }
    // jwt() deliberately skips CSRF in Spring Security 7; use authentication() to test browser protection.
    private org.springframework.test.web.servlet.request.RequestPostProcessor applicant(UUID user) {
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token").header("alg", "none").subject(user.toString()).build();
        return authentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token, List.of()));
    }
    private Financials finance() { return new Financials(new BigDecimal("1000000"), new BigDecimal("600000"), new BigDecimal("10000")); }
    private CabinetInput input(Financials finance) {
        return new CabinetInput("working-capital", new CreditApplicationRequest(business.getId(), new BigDecimal("1000000"), 24,
                CreditApplicationRequest.PaymentFrequency.MONTHLY, CreditApplicationRequest.FinancingPurpose.WORKING_CAPITAL, null), finance);
    }
    private UUID create() { return cabinet.create(owner, UUID.randomUUID(), input(finance())); }
    private UUID report(UUID id) { return cabinet.upload(owner, id, json.writeValueAsBytes(new CreditCabinetService.Report(business.getTaxId(), finance()))); }
    private UUID submitted() { UUID id = create(); report(id); cabinet.submit(owner, id); return id; }
    private void callback(UUID id, String status) throws Exception {
        var payload = Map.of("id", id, "status", status, "sum", "1000000.00", "months", 24, "percent", "20");
        mvc.perform(post("/scored").header("X-Scorer-Token", "cabinet-test-token")
                .contentType("application/json").content(json.writeValueAsString(payload))).andExpect(status().isNoContent());
    }
    private ResponseStatusException denied(org.junit.jupiter.api.function.Executable action, int code) {
        var exception = assertThrows(ResponseStatusException.class, action);
        assertEquals(code, exception.getStatusCode().value());
        return exception;
    }

    @Test void draftCannotBeScoredAndCreateRetriesAreIdempotentEvenConcurrently() throws Exception {
        UUID key = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(() -> cabinet.create(owner, key, input(finance())), () -> cabinet.create(owner, key, input(finance()))));
            assertEquals(futures.get(0).get(), futures.get(1).get());
        }
        UUID id = cabinet.create(owner, key, input(finance()));
        assertEquals("DRAFT", cabinet.view(owner, id).get("state"));
        assertEquals(CreditApplicationTask.Status.WAITING_INPUT, tasks.findById(id).orElseThrow().getStatus());
        scoring.processNext();
        assertEquals(0, tasks.findById(id).orElseThrow().getAttempts());
        var equivalent = new CabinetInput("working-capital", new CreditApplicationRequest(business.getId(), new BigDecimal("1000000.00"), 24,
                CreditApplicationRequest.PaymentFrequency.MONTHLY, CreditApplicationRequest.FinancingPurpose.WORKING_CAPITAL, null),
                new Financials(new BigDecimal("1000000.00"), new BigDecimal("600000.00"), new BigDecimal("10000.00")));
        assertEquals(id, cabinet.create(owner, key, equivalent));
        denied(() -> cabinet.create(owner, key, input(null)), 409);
    }

    @Test void requestForInformationCanBeCompletedAndSubmittedWithoutChangingApplicationId() {
        UUID id = cabinet.create(owner, UUID.randomUUID(), input(null));
        cabinet.submit(owner, id);
        assertEquals("FINANCIALS_REQUIRED", cabinet.view(owner, id).get("reasonCode"));
        cabinet.edit(owner, id, input(finance()));
        cabinet.submit(owner, id);
        assertEquals("FINANCIAL_REPORT_REQUIRED", cabinet.view(owner, id).get("reasonCode"));
        report(id);
        cabinet.submit(owner, id);
        cabinet.submit(owner, id);
        assertEquals("SUBMITTED", cabinet.view(owner, id).get("state"));
        assertNotNull(tasks.findById(id).orElseThrow().getRequestPayload());
        denied(() -> cabinet.edit(owner, id, input(null)), 409);
        denied(() -> report(id), 409);
    }

    @Test void reportValidationAndMatchingDoNotInventMissingData() {
        UUID id = create();
        denied(() -> cabinet.upload(owner, id, "not-json".getBytes()), 400);
        denied(() -> cabinet.upload(owner, id, "{\"inn\":\"77\",\"financials\":{}}".getBytes()), 400);
        cabinet.upload(owner, id, json.writeValueAsBytes(new CreditCabinetService.Report("other", finance())));
        cabinet.submit(owner, id);
        assertEquals("REPORT_MISMATCH", cabinet.view(owner, id).get("reasonCode"));
        UUID document = report(id);
        assertTrue(cabinet.download(viewer, id, document).length > 0);
        cabinet.submit(owner, id);
        assertEquals("SUBMITTED", cabinet.view(owner, id).get("state"));
    }

    @Test void approvalIsVisibleAndAcceptanceSigningAndDisbursementArePersistentAndIdempotent() throws Exception {
        UUID id = submitted();
        scoring.processNext();
        callback(id, "OK");
        var view = cabinet.view(owner, id);
        assertEquals("OFFERED", view.get("state"));
        assertNotNull(view.get("decision"));
        int events = ((List<?>) view.get("history")).size();
        callback(id, "OK");
        assertEquals(events, ((List<?>) cabinet.view(owner, id).get("history")).size());
        denied(() -> cabinet.action(owner, id, "disburse"), 409);
        for (String action : List.of("accept", "sign", "disburse")) {
            cabinet.action(owner, id, action);
            cabinet.action(owner, id, action);
        }
        assertEquals("DISBURSED_SIMULATED", cabinet.view(viewer, id).get("state"));
        mvc.perform(get("/api/credit/applications/" + id).with(applicant(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision.annualRate").value(20))
                .andExpect(jsonPath("$.state").value("DISBURSED_SIMULATED"));
    }

    @Test void companyScopeViewerPermissionsAndCsrfProtectEveryNewResource() throws Exception {
        UUID id = create(); UUID document = report(id);
        denied(() -> cabinet.view(other, id), 404);
        denied(() -> cabinet.download(other, id, document), 404);
        denied(() -> cabinet.edit(viewer, id, input(finance())), 403);
        denied(() -> cabinet.submit(viewer, id), 403);
        denied(() -> cabinet.action(viewer, id, "cancel"), 403);
        denied(() -> cabinet.upload(viewer, id, new byte[]{1}), 403);
        assertTrue(cabinet.list(other).isEmpty());
        assertEquals(1, cabinet.list(viewer).size());
        mvc.perform(post("/api/credit/applications").with(applicant(owner))
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(input(finance()))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/credit/applications")).andExpect(status().isUnauthorized());
    }

    @Test void apiValidationAndIdempotencyHeaderAreEnforced() throws Exception {
        mvc.perform(post("/api/credit/applications").with(applicant(owner)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/credit/applications").with(applicant(owner)).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(input(finance())))).andExpect(status().isBadRequest());
    }

    @Test void creditDeclineAndTechnicalFailureRemainDistinctAndLateResultsCanRecover() throws Exception {
        UUID rejected = submitted(); scoring.processNext(); callback(rejected, "DECLINED");
        assertEquals("REJECTED", cabinet.view(owner, rejected).get("state"));
        UUID failed = submitted();
        doThrow(new RestClientException("unavailable")).when(scorer).submit(any());
        scoring.processNext(); scoring.processNext();
        assertEquals("FAILED", cabinet.view(owner, failed).get("state"));
        callback(failed, "OK");
        assertEquals("OFFERED", cabinet.view(owner, failed).get("state"));
    }

    @Test void technicalRetryKeepsInputAndAcceptsALateResultBeforeRedispatch() throws Exception {
        UUID id = submitted();
        doThrow(new RestClientException("unavailable")).when(scorer).submit(any());
        scoring.processNext(); scoring.processNext();
        String snapshot = json.writeValueAsString(tasks.findById(id).orElseThrow().getRequestPayload());
        assertEquals("FAILED", cabinet.view(owner, id).get("state"));
        denied(() -> cabinet.action(viewer, id, "retry"), 403);
        cabinet.action(owner, id, "retry");
        cabinet.action(owner, id, "retry");
        assertEquals("SUBMITTED", cabinet.view(owner, id).get("state"));
        assertEquals(0, tasks.findById(id).orElseThrow().getAttempts());
        assertTrue(tasks.findById(id).orElseThrow().hasBeenDispatched());
        assertEquals(snapshot, json.writeValueAsString(tasks.findById(id).orElseThrow().getRequestPayload()));
        callback(id, "OK");
        assertEquals("OFFERED", cabinet.view(owner, id).get("state"));
        denied(() -> cabinet.action(owner, id, "retry"), 409);
    }

    @Test void cancelledApplicationsCannotBeReopenedByALateCallback() throws Exception {
        UUID id = submitted(); scoring.processNext(); cabinet.action(owner, id, "cancel");
        mvc.perform(post("/scored").header("X-Scorer-Token", "cabinet-test-token").contentType("application/json")
                .content(json.writeValueAsString(new ScorerResult(ScorerResult.Status.OK, 24, new BigDecimal("20"), new BigDecimal("1000000"), id))))
                .andExpect(status().isConflict());
        assertEquals("CANCELLED", cabinet.view(owner, id).get("state"));
    }

    @Test void expiredOfferCannotBeAcceptedAndHasAnAuditEvent() throws Exception {
        UUID id = submitted(); scoring.processNext(); callback(id, "OK");
        jdbc.update("UPDATE \"CreditDossier\" SET \"decision\" = jsonb_set(\"decision\", '{expiresAt}', '\"2020-01-01T00:00:00Z\"'::jsonb) WHERE \"applicationId\" = ?", id);
        cabinet.expireOffers();
        assertEquals("EXPIRED", cabinet.view(owner, id).get("state"));
        denied(() -> cabinet.action(owner, id, "accept"), 409);
    }

    @Test void productAndCompanyStopRulesAreValidatedWithoutGeneratingScoringResults() {
        var invalid = new CabinetInput("equipment", input(finance()).application(), finance());
        denied(() -> cabinet.create(owner, UUID.randomUUID(), invalid), 400);
        UUID id = create(); report(id);
        cabinet.updateCompany(owner, business.getId(), new CreditCabinetService.CompanyInput("Тест", LocalDate.of(2020, 1, 1), Business.RegistrationStatus.TERMINATED));
        cabinet.submit(owner, id);
        assertEquals("BUSINESS_NOT_ACTIVE", cabinet.view(owner, id).get("reasonCode"));
        assertNull(applications.findById(id).orElseThrow().getScorerResult());
        assertEquals(CreditApplication.Status.COMPLETED, applications.findById(id).orElseThrow().getStatus());
        assertEquals(0, tasks.findById(id).orElseThrow().getAttempts());
    }

    @Test void publicApiKeepsLargeFinancialAmountsExactAndServesItsContract() throws Exception {
        var input = input(new Financials(new BigDecimal("999999999999999.99"), new BigDecimal("600000"), new BigDecimal("10000")));
        String result = mvc.perform(post("/api/credit/applications").with(applicant(owner)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).get("applicationId").asText();
        mvc.perform(get("/api/credit/applications/" + id).with(applicant(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.financials.monthlyRevenue").value("999999999999999.99"));
        mvc.perform(get("/api/credit/openapi").with(applicant(owner))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openapi: 3.0.3")));
    }

    @Test void longLivedDraftsUseTheSubmissionDateForCompanyAgeAndScorerSnapshot() {
        UUID id = create(); report(id);
        jdbc.update("UPDATE \"CreditApplication\" SET \"creationTime\" = '2020-01-01T00:00:00Z' WHERE \"id\" = ?", id);
        cabinet.submit(owner, id);
        assertEquals("SUBMITTED", cabinet.view(owner, id).get("state"));
        var payload = tasks.findById(id).orElseThrow().getRequestPayload();
        assertEquals(LocalDate.now(java.time.ZoneOffset.UTC).toString(), ((Map<?, ?>) payload.get("parameters")).get("snapshot_on"));
    }

    @Test void draftEditsPersistAndSubmissionKeepsAnImmutableCompanySnapshot() {
        UUID id = create();
        var changed = new CabinetInput("working-capital", new CreditApplicationRequest(business.getId(), new BigDecimal("2000000"), 30,
                CreditApplicationRequest.PaymentFrequency.MONTHLY, CreditApplicationRequest.FinancingPurpose.WORKING_CAPITAL, null), finance());
        cabinet.edit(owner, id, changed);
        assertEquals(0, applications.findById(id).orElseThrow().getRequest().requestedAmount().compareTo(new BigDecimal("2000000")));
        assertEquals(30, applications.findById(id).orElseThrow().getRequest().termMonths());
        report(id); cabinet.submit(owner, id);
        String before = json.writeValueAsString(tasks.findById(id).orElseThrow().getRequestPayload());
        cabinet.updateCompany(owner, business.getId(), new CreditCabinetService.CompanyInput("Новое имя", LocalDate.of(2021, 1, 1), Business.RegistrationStatus.ACTIVE));
        String after = json.writeValueAsString(tasks.findById(id).orElseThrow().getRequestPayload());
        assertEquals(before, after);
        assertNotNull(jdbc.queryForObject("SELECT \"submissionSnapshot\"::text FROM \"CreditDossier\" WHERE \"applicationId\" = ?", String.class, id));
    }
}
