package dev.ammmmm.hackathon.smartcreditor.backend.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "\"user\"")
public class User {
    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    protected User() {
    }

    public User(String email, String passwordHash) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public UUID getId() {
        return id;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
