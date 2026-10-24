package dev.ammmmm.hackathon.smartcreditor.backend.service;

import dev.ammmmm.hackathon.smartcreditor.backend.client.ScorerClient;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplication;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationRequest;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationTask;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.CreditApplicationRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.CreditApplicationTaskRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class CreditApplicationService {
    private final CreditApplicationRepository applications;
    private final CreditApplicationTaskRepository tasks;
    private final @Nullable ScorerClient scorer;
    private final TransactionTemplate transactions;

    public CreditApplicationService(CreditApplicationRepository applications,
                         CreditApplicationTaskRepository tasks,
                         ObjectProvider<ScorerClient> scorerProvider,
                         PlatformTransactionManager transactionManager,
                         @Value("${credit.application.worker.enabled:false}") boolean processingEnabled) {
        this.applications = applications;
        this.tasks = tasks;
        this.scorer = processingEnabled ? scorerProvider.getObject() : null;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public UUID submit(UUID userId, CreditApplicationRequest request) {
        var application = new CreditApplication(userId, request);
        applications.saveAndFlush(application);
        tasks.save(new CreditApplicationTask(
                application.getId(), application.getCreationTime()));

        return application.getId();
    }

    @Scheduled(fixedDelayString = "${credit.application.worker.poll-delay-ms:1000}")
    public void processNext() {
        if (scorer == null) {
            return;
        }

        CreditApplication application = transactions.execute(transaction -> {
            var nextTask = tasks.findNextPending();
            if (nextTask.isEmpty()) {
                return null;
            }

            var task = nextTask.get();
            var claimedApplication = applications.findById(task.getApplicationId()).orElseThrow();

            task.setStatus(CreditApplicationTask.Status.PROCESSING);
            claimedApplication.markProcessing();
            return claimedApplication;
        });

        if (application == null) {
            return;
        }

        scorer.submit(application.getId(), application.getRequest());
        tasks.markAwaitingResult(application.getId());
    }
}
