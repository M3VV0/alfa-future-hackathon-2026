package ru.alfa.credit.core;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.alfa.credit.platform.TraceFilter;

@Controller
public class UiController implements ErrorController {
  @GetMapping("/")
  String home() {
    return "redirect:/app/applications";
  }

  @GetMapping("/login")
  String login() {
    return "login";
  }

  @RequestMapping("/error")
  String error(
      HttpServletRequest request, Model model, jakarta.servlet.http.HttpServletResponse response) {
    Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int status = value instanceof Number n ? n.intValue() : 503;
    response.setStatus(status);
    response.setHeader("Cache-Control", "no-store");
    model.addAttribute("errorStatus", status);
    model.addAttribute(
        "errorMessage",
        switch (status) {
          case 403 ->
              "Действие недоступно или сессия формы истекла. Обновите страницу и попробуйте снова.";
          case 404 -> "Страница не найдена или у вас нет доступа к этим данным.";
          case 413 -> "Файл слишком большой. Выберите документ до 10 МБ.";
          default -> "Сервис временно недоступен. Попробуйте ещё раз позже.";
        });
    model.addAttribute("traceId", TraceFilter.current());
    return "error";
  }
}
