package dev.ammmmm.hackathon.smartcreditor.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "BusinessAccessUserBusinessUnique", columnNames = {"userId", "businessId"}))
public class BusinessAccess {
    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private UUID businessId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    protected BusinessAccess() {
    }

    public BusinessAccess(UUID userId, UUID businessId, Role role) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId);
        this.businessId = Objects.requireNonNull(businessId);
        this.role = Objects.requireNonNull(role);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public Role getRole() {
        return role;
    }

    public enum Role {
        OWNER,
        ADMIN,
        VIEWER
    }
}
