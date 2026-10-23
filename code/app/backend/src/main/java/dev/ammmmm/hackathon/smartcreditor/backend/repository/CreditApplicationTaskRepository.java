package dev.ammmmm.hackathon.smartcreditor.backend.repository;

import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface CreditApplicationTaskRepository extends JpaRepository<CreditApplicationTask, UUID> {
    @Query(value = """
            SELECT task.*
              FROM "CreditApplicationTask" task
             WHERE task."status" = 'PENDING'
             ORDER BY task."creationTime", task."applicationId"
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<CreditApplicationTask> findNextPending();

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE "CreditApplicationTask"
               SET "status" = 'AWAITING_RESULT'
             WHERE "applicationId" = :applicationId
               AND "status" = 'PROCESSING'
            """, nativeQuery = true)
    int markAwaitingResult(@Param("applicationId") UUID applicationId);
}
