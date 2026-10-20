package ru.alfa.credit.core;

import org.springframework.context.annotation.*;
import org.springframework.web.servlet.ViewResolver;

/** Explicit backend-only boundary. Replace with Thymeleaf when frontend work is authorized. */
@Configuration
public class DeferredViews {
  @Bean
  ViewResolver deferredViewResolver() {
    return (name, locale) ->
        new org.springframework.web.servlet.View() {
          public String getContentType() {
            return "text/html;charset=UTF-8";
          }

          public void render(
              java.util.Map<String, ?> model,
              jakarta.servlet.http.HttpServletRequest request,
              jakarta.servlet.http.HttpServletResponse response)
              throws Exception {
            response.setStatus(501);
            response.setContentType(getContentType());
            response
                .getWriter()
                .write(
                    "<!doctype html><title>Backend only</title><p>Frontend templates are"
                        + " deferred.</p>");
          }
        };
  }
}
