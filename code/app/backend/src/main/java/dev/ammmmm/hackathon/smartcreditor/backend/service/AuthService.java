package dev.ammmmm.hackathon.smartcreditor.backend.service;

import dev.ammmmm.hackathon.smartcreditor.backend.model.AuthSession;
import dev.ammmmm.hackathon.smartcreditor.backend.model.User;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.AuthSessionRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.UserRepository;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@NullMarked
@Service
@Transactional
public class AuthService {
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    private static final Duration SESSION_TTL = Duration.ofDays(7);

    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder encoder;
    private final String issuer;
    private final String dummyPasswordHash;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, AuthSessionRepository sessions, PasswordEncoder passwordEncoder,
                       JwtEncoder encoder, @Value("${security.jwt.issuer}") String issuer) {
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.encoder = encoder;
        this.issuer = issuer;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public AuthTokens login(String email, String password) {
        var user = users.findByEmail(email);
        String passwordHash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);

        boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(password, passwordHash);
        } catch (IllegalArgumentException ex) {
            throw new BadCredentialsException("Invalid email or password");
        }
        if (user.isEmpty() || !passwordMatches) {
            throw new BadCredentialsException("Invalid email or password");
        }

        UUID sessionId = UUID.randomUUID();
        String refreshToken = newRefreshToken(sessionId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var session = new AuthSession(
                sessionId, user.orElseThrow().getId(), hashRefreshToken(refreshToken), now, now.plus(SESSION_TTL));
        sessions.save(session);

        return issueTokens(session, refreshToken);
    }

    public AuthTokens refresh(String refreshToken) {
        var session = findSessionByRefreshToken(refreshToken)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        String nextRefreshToken = newRefreshToken(session.getId());

        int updated = sessions.rotateRefreshToken(
                session.getId(), hashRefreshToken(refreshToken), hashRefreshToken(nextRefreshToken), Instant.now());
        if (updated != 1) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        return issueTokens(session, nextRefreshToken);
    }

    public void logout(@Nullable String refreshToken) {
        findSessionByRefreshToken(refreshToken).ifPresent(session ->
                sessions.deleteSession(session.getId(), session.getUserId()));
    }

    private Optional<AuthSession> findSessionByRefreshToken(@Nullable String refreshToken) {
        // UUID (36), separator (1), base64url secret without padding (43).
        if (refreshToken == null || refreshToken.length() != 80
                || refreshToken.charAt(36) != '.') {
            return Optional.empty();
        }

        UUID sessionId;
        try {
            sessionId = UUID.fromString(refreshToken.substring(0, 36));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }

        byte[] tokenHash = hashRefreshToken(refreshToken).getBytes(StandardCharsets.US_ASCII);
        return sessions.findByIdAndExpiresAtAfter(sessionId, Instant.now())
                .filter(session -> MessageDigest.isEqual(tokenHash,
                        session.getRefreshTokenHash().getBytes(StandardCharsets.US_ASCII)));
    }

    private String newRefreshToken(UUID sessionId) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return sessionId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private String hashRefreshToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private AuthTokens issueTokens(AuthSession session, String refreshToken) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(ACCESS_TOKEN_TTL);
        if (session.getExpiresAt().isBefore(expiresAt)) {
            expiresAt = session.getExpiresAt();
        }
        if (!expiresAt.isAfter(now)) {
            throw new BadCredentialsException("Authentication session has expired");
        }

        var claims = JwtClaimsSet.builder().issuer(issuer).subject(session.getUserId().toString())
                .claim("sid", session.getId().toString()).issuedAt(now).expiresAt(expiresAt).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new AuthTokens(accessToken, expiresAt, refreshToken, session.getExpiresAt());
    }

    public record AuthTokens(
            String accessToken, Instant accessExpiresAt, String refreshToken, Instant sessionExpiresAt) {
        @Override
        public String toString() {
            return "AuthTokens[REDACTED]";
        }
    }
}
