package ru.alfa.credit.platform;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(annotations = RestController.class)
public class InternalErrors {
  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem e) {
    return ResponseEntity.status(e.status)
        .body(Map.of("code", e.code, "trace_id", TraceFilter.current()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.unprocessableEntity()
        .body(Map.of("code", "INVALID_REQUEST", "trace_id", TraceFilter.current()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unavailable(Exception e) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .error("Internal request failed type={}", e.getClass().getSimpleName());
    return ResponseEntity.status(503)
        .body(Map.of("code", "TEMPORARILY_UNAVAILABLE", "trace_id", TraceFilter.current()));
  }
}
