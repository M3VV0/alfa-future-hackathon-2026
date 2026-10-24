package dev.ammmmm.hackathon.smartcreditor.backend;

import dev.ammmmm.hackathon.smartcreditor.backend.client.ScorerClient;
import dev.ammmmm.hackathon.smartcreditor.backend.model.Business;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplication;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationRequest;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationTask;
import dev.ammmmm.hackathon.smartcreditor.backend.model.ScoredResult;
import dev.ammmmm.hackathon.smartcreditor.backend.model.User;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.BusinessRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.CreditApplicationRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.CreditApplicationTaskRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.UserRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.service.CreditApplicationService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "credit.application.worker.enabled=true", "credit.application.worker.initial-delay-ms=3600000",
        "scorer.token=scoring-test-token", "scorer.retry-delay=PT0S", "scorer.max-attempts=2"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScoringExchangeTests {
    private static final String SCHEMA = "scorer_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String TOKEN = "scoring-test-token";

    // Queue tests must never claim real pending applications from the development schema.
    @DynamicPropertySource
    static void isolateDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropTestSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Autowired CreditApplicationService service;
    @Autowired CreditApplicationRepository applications;
    @Autowired CreditApplicationTaskRepository tasks;
    @Autowired BusinessRepository businesses;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean ScorerClient scorer;
    private User applicant;
    private Business business;

    @BeforeEach
    void fixtures() {
        applicant = users.saveAndFlush(new User(UUID.randomUUID() + "@scorer.test", "unused-test-hash"));
        business = new Business(Business.Type.LEGAL_ENTITY, "7700000000");
        business.setMspCategory(Business.MspCategory.MICRO);
        business.setMspAverageHeadcount(10);
        business.setMspIncludedOn(LocalDate.of(2020, 1, 1));
        business.setLocation(new Business.Location("77", "Москва", null, "Москва", null));
        business.setLegalEvents(List.of());
        business = businesses.saveAndFlush(business);
    }

    @AfterEach
    void removeFixtures() {
        jdbc.update("DELETE FROM \"CreditApplication\" WHERE \"businessId\" = ?", business.getId());
        businesses.deleteById(business.getId());
        users.deleteById(applicant.getId());
    }

    private UUID submit() {
        return service.submit(applicant.getId(), new CreditApplicationRequest(business.getId(),
                new BigDecimal("999999999999999.99"), 24, CreditApplicationRequest.PaymentFrequency.MONTHLY,
                CreditApplicationRequest.FinancingPurpose.EQUIPMENT, null));
    }

    private String decline(UUID id) {
        return "{\"id\":\"" + id + "\",\"status\":\"DECLINED\",\"months\":null,\"percent\":null,\"sum\":null}";
    }

    private void callback(String body, int expectedStatus) throws Exception {
        mvc.perform(post("/scored").header(ScorerClient.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void freezesParametersAndAcceptsDeclineBeforeHttpAcknowledgement() throws Exception {
        UUID id = submit();
        business.setMspAverageHeadcount(99);
        businesses.saveAndFlush(business);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Map<String, Object> payload = invocation.getArgument(0);
            assertEquals(id.toString(), payload.get("id"));
            Map<?, ?> parameters = (Map<?, ?>) payload.get("parameters");
            assertEquals("999999999999999.99", parameters.get("requested_amount"));
            assertEquals(10, parameters.get("average_headcount"));
            assertEquals("micro", parameters.get("msp_category"));
            assertEquals("legal_entity", parameters.get("applicant_entity_type"));
            assertEquals("77", ((Map<?, ?>) parameters.get("location")).get("region_code"));
            assertNotNull(parameters.get("snapshot_on"));
            callback(decline(id), 204);
            return null;
        }).when(scorer).submit(any());

        service.processNext();
        assertEquals(CreditApplicationTask.Status.COMPLETED, tasks.findById(id).orElseThrow().getStatus());
        var application = applications.findById(id).orElseThrow();
        assertEquals(CreditApplication.Status.COMPLETED, application.getStatus());
        assertEquals(ScoredResult.Status.DECLINED, application.getScoredResult().status());
        Instant scoredAt = application.getScoredAt();
        callback(decline(id), 204);
        assertEquals(scoredAt, applications.findById(id).orElseThrow().getScoredAt());
        callback("{\"id\":\"" + id + "\",\"status\":\"OK\",\"months\":12,\"percent\":15,\"sum\":100000}", 409);
    }

    @Test
    void treatsEquivalentDecimalRepresentationsAsDuplicateResult() throws Exception {
        UUID id = submit();
        service.processNext();
        callback("{\"id\":\"" + id + "\",\"status\":\"OFFER\",\"months\":12,\"percent\":15,\"sum\":100000}", 204);
        callback("{\"id\":\"" + id + "\",\"status\":\"OFFER\",\"months\":12,\"percent\":15.0000,\"sum\":100000.00}", 204);
    }

    @Test
    void callbackRequiresServiceTokenEvenForAuthenticatedBrowser() throws Exception {
        UUID id = submit();
        mvc.perform(post("/scored").contentType(MediaType.APPLICATION_JSON).content(decline(id)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/scored").with(user("customer"))
                        .header(ScorerClient.TOKEN_HEADER, "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(decline(id)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/credit/application/submit").with(user("customer"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnknownUnsentAndInvalidResults() throws Exception {
        callback(decline(UUID.randomUUID()), 404);
        UUID id = submit();
        callback(decline(id), 409);
        for (String body : List.of("{}", "{\"status\":\"UNKNOWN\",\"id\":\"" + id + "\"}",
                "{\"status\":0,\"id\":\"" + id + "\"}",
                "{\"status\":\"OK\",\"id\":\"" + id + "\"}",
                "{\"status\":\"DECLINED\",\"months\":-1,\"id\":\"" + id + "\"}",
                "{\"status\":\"DECLINED\",\"sum\":-1,\"id\":\"" + id + "\"}")) {
            callback(body, 400);
        }
        assertEquals(CreditApplication.Status.PENDING, applications.findById(id).orElseThrow().getStatus());
    }

    @Test
    void retriesNetworkFailureWithTheSameSnapshot() {
        UUID id = submit();
        List<Map<String, Object>> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            if (sent.size() == 1) throw new RestClientException("temporary outage");
            return null;
        }).when(scorer).submit(any());
        service.processNext();
        assertEquals(CreditApplicationTask.Status.PENDING, tasks.findById(id).orElseThrow().getStatus());
        service.processNext();
        assertEquals(2, sent.size());
        assertEquals(sent.get(0), sent.get(1));
        assertEquals(2, tasks.findById(id).orElseThrow().getAttempts());
        assertEquals(CreditApplicationTask.Status.AWAITING_RESULT, tasks.findById(id).orElseThrow().getStatus());
    }

    @Test
    void permanentHttpFailureDoesNotRetry() {
        UUID id = submit();
        doThrow(new RestClientResponseException("invalid request", HttpStatus.BAD_REQUEST,
                "Bad Request", HttpHeaders.EMPTY, null, null)).when(scorer).submit(any());
        service.processNext();
        service.processNext();
        verify(scorer, times(1)).submit(any());
        assertEquals(CreditApplication.Status.FAILED, applications.findById(id).orElseThrow().getStatus());
        assertEquals("SCORER_HTTP_400", tasks.findById(id).orElseThrow().getLastError());
    }

    @Test
    void callbackTimeoutRetriesThenFailsButStillAcceptsLateResult() throws Exception {
        UUID id = submit();
        service.processNext();
        expire(id);
        service.processNext();
        expire(id);
        service.processNext();
        verify(scorer, times(2)).submit(any());
        assertEquals(CreditApplication.Status.FAILED, applications.findById(id).orElseThrow().getStatus());
        assertEquals("SCORER_TIMEOUT", tasks.findById(id).orElseThrow().getLastError());
        callback(decline(id), 204);
        assertEquals(CreditApplication.Status.COMPLETED, applications.findById(id).orElseThrow().getStatus());
    }

    @Test
    void recoversTaskAbandonedByWorker() {
        UUID id = submit();
        var task = tasks.findById(id).orElseThrow();
        task.claim(Instant.now().minusSeconds(60), Instant.now().minusSeconds(30));
        tasks.saveAndFlush(task);
        service.processNext();
        assertEquals(2, tasks.findById(id).orElseThrow().getAttempts());
        assertEquals(CreditApplicationTask.Status.AWAITING_RESULT, tasks.findById(id).orElseThrow().getStatus());
    }

    @Test
    void concurrentWorkersDoNotSendTheSameLiveTaskTwice() throws Exception {
        submit();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(scorer).submit(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(service::processNext);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                service.processNext();
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        verify(scorer, times(1)).submit(any());
    }

    private void expire(UUID id) {
        jdbc.update("UPDATE \"CreditApplicationTask\" SET \"nextAttemptAt\" = ? WHERE \"applicationId\" = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
    }
}
