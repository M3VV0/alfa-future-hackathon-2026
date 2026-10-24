package dev.ammmmm.hackathon.smartcreditor.backend.repository;

import dev.ammmmm.hackathon.smartcreditor.backend.model.Business;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessRepository extends JpaRepository<Business, UUID> {
}
