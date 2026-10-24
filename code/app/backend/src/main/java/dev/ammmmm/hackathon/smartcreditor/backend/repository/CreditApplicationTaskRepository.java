package dev.ammmmm.hackathon.smartcreditor.backend.repository;

import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CreditApplicationTaskRepository extends JpaRepository<CreditApplicationTask, UUID> {
    @Query(value = """
            SELECT task.*
              FROM "CreditApplicationTask" task
             WHERE task."status" IN ('PENDING', 'PROCESSING', 'AWAITING_RESULT')
               AND task."nextAttemptAt" <= :now
             ORDER BY task."nextAttemptAt", task."creationTime", task."applicationId"
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<CreditApplicationTask> findNextReady(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT task FROM CreditApplicationTask task WHERE task.applicationId = :id")
    Optional<CreditApplicationTask> findByIdForUpdate(@Param("id") UUID applicationId);
}
