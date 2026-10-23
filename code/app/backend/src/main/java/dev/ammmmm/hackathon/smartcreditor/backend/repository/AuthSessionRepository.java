package dev.ammmmm.hackathon.smartcreditor.backend.repository;

import dev.ammmmm.hackathon.smartcreditor.backend.model.AuthSession;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@NullMarked
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    Optional<AuthSession> findByIdAndExpiresAtAfter(UUID id, Instant now);

    boolean existsByIdAndUserIdAndExpiresAtAfter(UUID id, UUID userId, Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuthSession session
               SET session.refreshTokenHash = :newHash
             WHERE session.id = :sessionId
               AND session.refreshTokenHash = :oldHash
               AND session.expiresAt > :now
            """)
    int rotateRefreshToken(@Param("sessionId") UUID sessionId, @Param("oldHash") String oldHash,
                           @Param("newHash") String newHash, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM AuthSession session
                  WHERE session.id = :sessionId
                    AND session.userId = :userId
            """)
    void deleteSession(@Param("sessionId") UUID sessionId, @Param("userId") UUID userId);
}
