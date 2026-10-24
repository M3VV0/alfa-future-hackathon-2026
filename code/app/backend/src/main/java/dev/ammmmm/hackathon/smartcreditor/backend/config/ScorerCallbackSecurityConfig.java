package dev.ammmmm.hackathon.smartcreditor.backend.config;

import dev.ammmmm.hackathon.smartcreditor.backend.client.ScorerClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration
public class ScorerCallbackSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain scorerCallbackSecurity(HttpSecurity http, @Value("${scorer.token:}") String token) {
        var callback = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/scored");
        http.securityMatcher(callback)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("SCORER"))
                .addFilterAfter(new CallbackTokenFilter(token), LogoutFilter.class);
        return http.build();
    }

    private static class CallbackTokenFilter extends OncePerRequestFilter {
        private final String token;

        private CallbackTokenFilter(String token) {
            this.token = token;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String supplied = request.getHeader(ScorerClient.TOKEN_HEADER);
            if (token.isBlank() || supplied == null || !MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("scorer", null,
                    List.of(new SimpleGrantedAuthority("ROLE_SCORER"))));
            SecurityContextHolder.setContext(context);
            chain.doFilter(request, response);
        }
    }
}
