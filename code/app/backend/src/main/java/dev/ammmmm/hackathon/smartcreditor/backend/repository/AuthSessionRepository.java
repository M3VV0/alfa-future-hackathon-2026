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
            update AuthSession session
               set session.refreshTokenHash = :newHash
             where session.id = :sessionId
               and session.refreshTokenHash = :oldHash
               and session.expiresAt > :now""")
    int rotateRefreshToken(@Param("sessionId") UUID sessionId, @Param("oldHash") String oldHash,
                           @Param("newHash") String newHash, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from AuthSession session
             where session.id = :sessionId
               and session.userId = :userId""")
    void deleteSession(@Param("sessionId") UUID sessionId, @Param("userId") UUID userId);
}
