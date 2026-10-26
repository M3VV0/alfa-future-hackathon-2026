package dev.ammmmm.hackathon.smartcreditor.backend.service;

import dev.ammmmm.hackathon.smartcreditor.backend.client.ScorerClient;
import dev.ammmmm.hackathon.smartcreditor.backend.credit.CreditCabinetService;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplication;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationRequest;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationTask;
import dev.ammmmm.hackathon.smartcreditor.backend.model.ScorerResult;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.BusinessRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.CreditApplicationRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.CreditApplicationTaskRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class CreditApplicationService {
    private static final Logger log = LoggerFactory.getLogger(CreditApplicationService.class);
    private final CreditApplicationRepository applications;
    private final CreditApplicationTaskRepository tasks;
    private final BusinessRepository businesses;
    private final @Nullable ScorerClient scorer;
    private final TransactionTemplate transactions;
    private final CreditCabinetService cabinet;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Duration requestLease;
    private final Duration callbackTimeout;

    public CreditApplicationService(CreditApplicationRepository applications,
                                    CreditApplicationTaskRepository tasks,
                                    BusinessRepository businesses,
                                    ObjectProvider<ScorerClient> scorerProvider,
                                    PlatformTransactionManager transactionManager,
                                    CreditCabinetService cabinet,
                                    @Value("${credit.application.worker.enabled:false}") boolean enabled,
                                    @Value("${scorer.max-attempts:5}") int maxAttempts,
                                    @Value("${scorer.retry-delay:PT5S}") Duration retryDelay,
                                    @Value("${scorer.request-lease:PT30S}") Duration requestLease,
                                    @Value("${scorer.callback-timeout:PT60S}") Duration callbackTimeout) {
        if (maxAttempts < 1 || retryDelay.isNegative() || requestLease.isNegative() || requestLease.isZero()
                || callbackTimeout.isNegative() || callbackTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid scorer retry or timeout configuration");
        }
        this.cabinet = cabinet;
        this.applications = applications;
        this.tasks = tasks;
        this.businesses = businesses;
        this.scorer = enabled ? scorerProvider.getObject() : null;
        this.transactions = new TransactionTemplate(transactionManager);
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
        this.requestLease = requestLease;
        this.callbackTimeout = callbackTimeout;
    }

    @Transactional
    public UUID submit(UUID userId, CreditApplicationRequest request) {
        var application = new CreditApplication(userId, request);
        applications.saveAndFlush(application);
        tasks.save(new CreditApplicationTask(
                application.getId(), application.getCreationTime(), createScorerPayload(application)));
        return application.getId();
    }

    @Scheduled(fixedDelayString = "${credit.application.worker.poll-delay-ms:1000}",
            initialDelayString = "${credit.application.worker.initial-delay-ms:1000}")
    public void processNext() {
        if (scorer == null) return;
        Dispatch dispatch = transactions.execute(transaction -> claimNext());
        if (dispatch == null) return;

        try {
            // Do not hold database locks while waiting for the scorer: a callback
            // can commit before the scorer returns its 202 acknowledgement.
            scorer.submit(dispatch.payload());
        } catch (RestClientException error) {
            boolean permanent = error instanceof RestClientResponseException response
                    && response.getStatusCode().is4xxClientError()
                    && response.getStatusCode().value() != 408 && response.getStatusCode().value() != 429;
            String code = error instanceof RestClientResponseException response
                    ? "SCORER_HTTP_" + response.getStatusCode().value() : "SCORER_UNAVAILABLE";
            finishDispatch(dispatch, code, permanent);
            log.warn("Scorer dispatch failed: application={}, attempt={}, code={}",
                    dispatch.id(), dispatch.attempt(), code);
            return;
        }
        finishDispatch(dispatch, null, false);
    }

    private Dispatch claimNext() {
        Instant now = Instant.now();
        var next = tasks.findNextReady(now);
        if (next.isEmpty()) return null;
        var task = next.get();
        var application = applications.findById(task.getApplicationId()).orElseThrow();
        if (task.getAttempts() >= maxAttempts) {
            task.fail(now, "SCORER_TIMEOUT");
            application.markFailed();
            cabinet.scoringFailed(application.getId(), "SCORER_TIMEOUT");
            return null;
        }
        if (task.getRequestPayload() == null) task.setRequestPayload(createScorerPayload(application));
        task.claim(now, now.plus(requestLease));
        application.markProcessing();
        cabinet.scoringStarted(application.getId());
        return new Dispatch(application.getId(), task.getAttempts(), task.getRequestPayload());
    }

    private Map<String, Object> createScorerPayload(CreditApplication application) {
        var business = businesses.findById(application.getRequest().businessId()).orElseThrow();
        return ScorerClient.createPayload(application, business);
    }

    private void finishDispatch(Dispatch dispatch, @Nullable String error, boolean permanent) {
        transactions.executeWithoutResult(transaction -> {
            var task = tasks.findByIdForUpdate(dispatch.id()).orElseThrow();
            // A late ACK or expired attempt must not overwrite a callback or newer dispatch.
            if (task.getStatus() != CreditApplicationTask.Status.PROCESSING
                    || task.getAttempts() != dispatch.attempt()) return;
            Instant now = Instant.now();
            if (error == null) {
                task.awaitResult(now, now.plus(callbackTimeout));
            } else if (permanent || task.getAttempts() >= maxAttempts) {
                task.fail(now, error);
                applications.findById(dispatch.id()).orElseThrow().markFailed();
                cabinet.scoringFailed(dispatch.id(), error);
            } else {
                task.retry(now, now.plus(retryDelay), error);
            }
        });
    }

    @Transactional
    public void acceptScorerResult(ScorerResult result) {
        var task = tasks.findByIdForUpdate(result.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found"));
        var application = applications.findById(result.id()).orElseThrow();
        if (application.getScorerResult() != null) {
            if (!application.getScorerResult().sameResult(result)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A different scoring result is already stored");
            }
            return;
        }
        if (!task.hasBeenDispatched()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Application has not been dispatched");
        }
        Instant now = Instant.now();
        cabinet.scoringCompleted(application, result, now);
        application.complete(result, now);
        task.complete(now);
    }

    private record Dispatch(UUID id, int attempt, Map<String, Object> payload) {
    }
}
