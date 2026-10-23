package dev.ammmmm.hackathon.smartcreditor.backend.repository;

import dev.ammmmm.hackathon.smartcreditor.backend.model.BusinessAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BusinessAccessRepository extends JpaRepository<BusinessAccess, UUID> {
    Optional<BusinessAccess> findByUserIdAndBusinessId(UUID userId, UUID businessId);
}
