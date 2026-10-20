package ru.alfa.credit.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import ru.alfa.credit.platform.*;

@ControllerAdvice(assignableTypes = WebController.class)
public class WebErrors {
  private final Ui ui;

  public WebErrors(Ui ui) {
    this.ui = ui;
  }

  @ExceptionHandler(Problem.class)
  ModelAndView problem(
      Problem e, HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
    return error(e.status, e.code, req, res);
  }

  @ExceptionHandler({
    org.springframework.validation.BindException.class,
    org.springframework.web.method.annotation.HandlerMethodValidationException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ModelAndView invalid(
      Exception e, HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
    return error(422, "INVALID_FORM", req, res);
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ModelAndView large(
      Exception e, HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
    return error(422, "DOCUMENT_SIZE", req, res);
  }

  @ExceptionHandler(Exception.class)
  ModelAndView unavailable(
      Exception e, HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .error("Web request failed type={}", e.getClass().getSimpleName());
    return error(503, "TEMPORARILY_UNAVAILABLE", req, res);
  }

  private ModelAndView error(
      int status,
      String code,
      HttpServletRequest req,
      jakarta.servlet.http.HttpServletResponse res) {
    boolean hx = "true".equalsIgnoreCase(req.getHeader("HX-Request"));
    if (hx) {
      res.setHeader("HX-Retarget", "#form-feedback");
      res.setHeader("HX-Reswap", "innerHTML");
    }
    res.setHeader("Cache-Control", "no-store");
    return new ModelAndView(
        hx ? "fragments/feedback :: message" : "error",
        Map.of(
            "errorStatus",
            status,
            "errorMessage",
            ui.error(code),
            "errorCode",
            code,
            "traceId",
            TraceFilter.current()),
        HttpStatusCode.valueOf(status));
  }
}
