package ru.alfa.credit.core;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import ru.alfa.credit.platform.*;

@ControllerAdvice(assignableTypes = WebController.class)
public class WebErrors {
  @ExceptionHandler(Problem.class)
  ResponseEntity<String> problem(Problem e) {
    return error(e.status, e.code);
  }

  @ExceptionHandler({
    org.springframework.validation.BindException.class,
    org.springframework.web.method.annotation.HandlerMethodValidationException.class
  })
  ResponseEntity<String> invalid(Exception e) {
    return error(422, "INVALID_FORM");
  }

  @ExceptionHandler({
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<String> missing(Exception e) {
    return error(422, "INVALID_FORM");
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<String> large(Exception e) {
    return error(422, "DOCUMENT_SIZE");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<String> unavailable(Exception e) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .error("Web request failed type={}", e.getClass().getSimpleName());
    return error(503, "TEMPORARILY_UNAVAILABLE");
  }

  private ResponseEntity<String> error(int status, String code) {
    return ResponseEntity.status(status)
        .contentType(MediaType.TEXT_HTML)
        .header("Cache-Control", "no-store")
        .body(
            "<!doctype html><title>"
                + status
                + "</title><p>"
                + code
                + "</p><p>trace_id: "
                + TraceFilter.current()
                + "</p>");
  }
}
