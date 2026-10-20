package ru.alfa.credit.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.session.web.http.DefaultCookieSerializer;
import ru.alfa.credit.platform.ServiceTokenFilter;

@Configuration
public class Security {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService userDetailsService(JdbcTemplate db) {
    return username ->
        db
            .query(
                "select username,password_hash from auth.app_user where username=?",
                (rs, n) ->
                    User.withUsername(rs.getString(1))
                        .password(rs.getString(2))
                        .roles("CLIENT")
                        .build(),
                username)
            .stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }

  @Bean
  @Order(1)
  SecurityFilterChain internal(HttpSecurity http, @Value("${security.service-token}") String token)
      throws Exception {
    return http.securityMatcher("/internal/**", "/actuator/prometheus")
        .csrf(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(new ServiceTokenFilter(token), UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(c -> c.anyRequest().hasRole("SERVICE"))
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain web(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            c ->
                c.requestMatchers(
                        "/login",
                        "/error",
                        "/actuator/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/openapi/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(c -> c.defaultSuccessUrl("/app/applications", true))
        .logout(
            c ->
                c.logoutSuccessUrl("/login?logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("SESSION"))
        .exceptionHandling(
            c ->
                c.defaultAuthenticationEntryPointFor(
                        (req, res, e) -> {
                          res.setHeader("HX-Redirect", "/login");
                          res.setStatus(401);
                        },
                        req -> "true".equalsIgnoreCase(req.getHeader("HX-Request")))
                    .defaultAuthenticationEntryPointFor(
                        new org.springframework.security.web.authentication
                            .LoginUrlAuthenticationEntryPoint("/login"),
                        org.springframework.security.web.util.matcher.AnyRequestMatcher.INSTANCE))
        .headers(
            c ->
                c.contentSecurityPolicy(
                    p ->
                        p.policyDirectives(
                            "default-src 'self'; frame-ancestors 'none'; object-src 'none'")))
        .build();
  }

  @Bean
  DefaultCookieSerializer cookieSerializer(
      @Value("${security.cookie-secure:false}") boolean secure) {
    var cookie = new DefaultCookieSerializer();
    cookie.setUseHttpOnlyCookie(true);
    cookie.setSameSite("Lax");
    cookie.setUseSecureCookie(secure);
    return cookie;
  }
}
