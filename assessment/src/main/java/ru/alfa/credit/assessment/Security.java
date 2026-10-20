package ru.alfa.credit.assessment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ru.alfa.credit.platform.ServiceTokenFilter;

@Configuration
public class Security {
  @Bean
  SecurityFilterChain internal(HttpSecurity http, @Value("${security.service-token}") String token)
      throws Exception {
    // No browser session is accepted by assessment; credentials are required on every service
    // request.
    return http.csrf(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new ServiceTokenFilter(token) {
              @Override
              protected boolean shouldNotFilter(jakarta.servlet.http.HttpServletRequest req) {
                return req.getRequestURI().startsWith("/actuator/health");
              }
            },
            UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            c ->
                c.requestMatchers("/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .hasRole("SERVICE"))
        .build();
  }
}
