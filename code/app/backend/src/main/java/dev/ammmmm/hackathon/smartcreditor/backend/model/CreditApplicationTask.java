package dev.ammmmm.hackathon.smartcreditor.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
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

    protected CreditApplicationTask() {
    }

    public CreditApplicationTask(UUID applicationId, Instant creationTime) {
        this.applicationId = Objects.requireNonNull(applicationId);
        this.creationTime = Objects.requireNonNull(creationTime);
        this.status = Status.PENDING;
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

    public void setStatus(Status status) {
        this.status = status;
    }

    public enum Status {
        PENDING,
        PROCESSING,
        AWAITING_RESULT
    }
}
