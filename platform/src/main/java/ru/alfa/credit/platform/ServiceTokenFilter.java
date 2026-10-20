package ru.alfa.credit.platform;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class ServiceTokenFilter extends OncePerRequestFilter {
  private final byte[] token;

  public ServiceTokenFilter(String token) {
    if (token.length() < 32)
      throw new IllegalArgumentException("Service token must contain at least 32 characters");
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String supplied = req.getHeader("X-Service-Token");
    if (supplied == null
        || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
      res.setStatus(401);
      res.setContentType("application/json");
      res.getWriter()
          .write(
              "{\"code\":\"SERVICE_AUTH_REQUIRED\",\"trace_id\":\""
                  + TraceFilter.current()
                  + "\"}");
      return;
    }
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "service", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
    chain.doFilter(req, res);
  }
}
