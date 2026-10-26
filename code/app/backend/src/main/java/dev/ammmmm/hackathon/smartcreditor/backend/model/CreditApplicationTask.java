package dev.ammmmm.hackathon.smartcreditor.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(indexes = @Index(name = "CreditApplicationTaskQueueIndex", columnList = "status, creationTime, applicationId"))
public class CreditApplicationTask {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID applicationId;

    @Column(nullable = false, updatable = false)
    private Instant creationTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> requestPayload;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(length = 64)
    private String lastError;

    protected CreditApplicationTask() {
    }

    public CreditApplicationTask(UUID applicationId, Instant creationTime) {
        this.applicationId = Objects.requireNonNull(applicationId);
        this.creationTime = Objects.requireNonNull(creationTime);
        this.status = Status.PENDING;
        this.nextAttemptAt = creationTime;
        this.updatedAt = creationTime;
    }

    public CreditApplicationTask(UUID applicationId, Instant creationTime, Map<String, Object> requestPayload) {
        this(applicationId, creationTime);
        this.requestPayload = Objects.requireNonNull(requestPayload);
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public Status getStatus() {
        return status;
    }

    public Map<String, Object> getRequestPayload() {
        return requestPayload;
    }

    public void setRequestPayload(Map<String, Object> requestPayload) {
        this.requestPayload = Objects.requireNonNull(requestPayload);
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void pause() {
        status = Status.WAITING_INPUT;
    }

    public void claim(Instant now, Instant leaseExpiresAt) {
        status = Status.PROCESSING;
        attempts++;
        updatedAt = now;
        nextAttemptAt = leaseExpiresAt;
        lastError = null;
    }

    public void awaitResult(Instant now, Instant deadline) {
        status = Status.AWAITING_RESULT;
        updatedAt = now;
        nextAttemptAt = deadline;
    }

    public void retry(Instant now, Instant retryAt, String error) {
        status = Status.PENDING;
        updatedAt = now;
        nextAttemptAt = retryAt;
        lastError = error;
    }

    public void complete(Instant now) {
        status = Status.COMPLETED;
        updatedAt = now;
        lastError = null;
    }

    public void fail(Instant now, String error) {
        status = Status.FAILED;
        updatedAt = now;
        lastError = error;
    }

    public enum Status {
        WAITING_INPUT,
        PENDING,
        PROCESSING,
        AWAITING_RESULT,
        COMPLETED,
        FAILED
    }
}
