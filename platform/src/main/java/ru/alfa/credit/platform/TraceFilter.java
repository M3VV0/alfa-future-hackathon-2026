package ru.alfa.credit.platform;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String trace = request.getHeader("X-Trace-Id");
    if (trace == null || !trace.matches("[a-zA-Z0-9-]{1,64}")) trace = UUID.randomUUID().toString();
    MDC.put("trace_id", trace);
    response.setHeader("X-Trace-Id", trace);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.clear();
    }
  }

  public static String current() {
    String value = MDC.get("trace_id");
    return value == null ? UUID.randomUUID().toString() : value;
  }
}
