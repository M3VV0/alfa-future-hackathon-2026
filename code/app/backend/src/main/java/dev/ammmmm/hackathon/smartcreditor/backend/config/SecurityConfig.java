package dev.ammmmm.hackathon.smartcreditor.backend.config;

import dev.ammmmm.hackathon.smartcreditor.backend.repository.AuthSessionRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@NullMarked
@Configuration
public class SecurityConfig {
    private static final OAuth2Error INVALID_SESSION = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN, "Invalid or expired authentication session", null);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder, CsrfTokenRepository csrfTokens) {
        var paths = PathPatternRequestMatcher.withDefaults();
        var publicEndpoints = new OrRequestMatcher(
                paths.matcher("/api/health"),
                paths.matcher(HttpMethod.GET, "/assets/**"),
                paths.matcher(HttpMethod.GET, "/auth/csrf"),
                paths.matcher(HttpMethod.GET, "/login"),
                paths.matcher(HttpMethod.POST, "/login"),
                paths.matcher(HttpMethod.POST, "/auth/refresh"),
                paths.matcher(HttpMethod.POST, "/logout"));

        var apiEndpoints = paths.matcher("/api/**");
        var loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login");
        AuthenticationEntryPoint entryPoint =
                (request, response, exception) -> {
                    if (apiEndpoints.matches(request)) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    } else if ("true".equals(request.getHeader("HX-Request"))) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setHeader("HX-Redirect", request.getContextPath() + "/login");
                    } else {
                        loginEntryPoint.commence(request, response, exception);
                    }
                };

        http.authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(publicEndpoints).permitAll().anyRequest().authenticated());
        http.sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.requestCache(cache -> cache.requestCache(new NullRequestCache()));
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens));
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(entryPoint).accessDeniedHandler(
                        (request, response, exception) ->
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN)));

        // Resource Server DSL exempts resolved tokens from CSRF checks.
        // Register the standard filter directly to retain CSRF for JWT cookies.
        http.addFilter(jwtFilter(decoder, publicEndpoints, entryPoint));
        return http.build();
    }

    private BearerTokenAuthenticationFilter jwtFilter(
            JwtDecoder decoder, RequestMatcher publicEndpoints, AuthenticationEntryPoint entryPoint) {
        var converter = new BearerTokenAuthenticationConverter();
        converter.setBearerTokenResolver(request -> {
            if (publicEndpoints.matches(request)) {
                return null;
            }
            var cookie = WebUtils.getCookie(request, "access_token");
            return cookie == null ? null : cookie.getValue();
        });
        var filter = new BearerTokenAuthenticationFilter(
                new ProviderManager(new JwtAuthenticationProvider(decoder)), converter);
        filter.setAuthenticationEntryPoint(entryPoint);
        return filter;
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(@Value("${security.auth.secure-cookies:true}") boolean secureCookies) {
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieName(secureCookies ? "__Host-XSRF-TOKEN" : "XSRF-TOKEN");
        repository.setCookieCustomizer(cookie ->
                cookie.httpOnly(true).secure(secureCookies).sameSite("Lax").path("/"));
        return repository;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    RSAPublicKey jwtPublicKey(@Value("${security.jwt.public-key}") Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            return Objects.requireNonNull(RsaKeyConverters.x509().convert(input), "Invalid JWT public key");
        }
    }

    @Bean
    RSAPrivateKey jwtPrivateKey(@Value("${security.jwt.private-key}") Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            return Objects.requireNonNull(RsaKeyConverters.pkcs8().convert(input), "Invalid JWT private key");
        }
    }

    @Bean
    JwtEncoder jwtEncoder(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        return NimbusJwtEncoder.withKeyPair(publicKey, privateKey).algorithm(SignatureAlgorithm.RS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(RSAPublicKey publicKey,
                          @Value("${security.jwt.issuer}") String issuer,
                          AuthSessionRepository sessions) {
        var decoder = NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var standardValidator = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(jwt -> {
            var result = standardValidator.validate(jwt);
            if (result.hasErrors()) {
                return result;
            }
            return validateSession(jwt, sessions);
        });
        return decoder;
    }

    private OAuth2TokenValidatorResult validateSession(Jwt jwt, AuthSessionRepository sessions) {
        if (!(jwt.getClaims().get("sub") instanceof String userIdClaim)
                || !(jwt.getClaims().get("sid") instanceof String sessionIdClaim) || jwt.getExpiresAt() == null) {
            return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
        }

        UUID userId;
        UUID sessionId;
        try {
            userId = UUID.fromString(userIdClaim);
            sessionId = UUID.fromString(sessionIdClaim);
        } catch (IllegalArgumentException ex) {
            return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
        }

        if (!sessions.existsByIdAndUserIdAndExpiresAtAfter(sessionId, userId, Instant.now())) {
            return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
