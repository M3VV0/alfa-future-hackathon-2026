package dev.ammmmm.hackathon.smartcreditor.backend.repository;

import dev.ammmmm.hackathon.smartcreditor.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}
