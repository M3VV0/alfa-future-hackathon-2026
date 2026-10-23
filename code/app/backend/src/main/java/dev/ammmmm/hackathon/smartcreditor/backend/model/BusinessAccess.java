package dev.ammmmm.hackathon.smartcreditor.backend.model;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "business_access", uniqueConstraints =
        @UniqueConstraint(columnNames = {"user_id", "business_id"}))
public class BusinessAccess {
    public enum Role {
        OWNER,
        ADMIN,
        VIEWER
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "business_id", nullable = false, updatable = false)
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
}
