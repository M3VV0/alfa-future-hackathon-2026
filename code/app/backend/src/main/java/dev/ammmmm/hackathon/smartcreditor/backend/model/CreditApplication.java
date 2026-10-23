package dev.ammmmm.hackathon.smartcreditor.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
public class CreditApplication {
    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private Instant creationTime;

    @NotNull
    @Valid
    @Embedded
    private CreditApplicationRequest request;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    protected CreditApplication() {
    }

    public CreditApplication(UUID userId, CreditApplicationRequest request) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId);
        this.creationTime = Instant.now();
        this.request = Objects.requireNonNull(request);
        this.status = Status.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public CreditApplicationRequest getRequest() {
        return request;
    }

    public Status getStatus() {
        return status;
    }

    public void markProcessing() {
        this.status = Status.PROCESSING;
    }

    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
