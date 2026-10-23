package dev.ammmmm.hackathon.smartcreditor.backend.repository;

import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CreditApplicationRepository extends JpaRepository<CreditApplication, UUID> {
}
