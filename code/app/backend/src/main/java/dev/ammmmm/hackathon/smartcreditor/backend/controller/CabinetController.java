package dev.ammmmm.hackathon.smartcreditor.backend.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class CabinetController {
    @GetMapping({"/app/applications", "/app/applications/{id}", "/app/companies"})
    public ModelAndView applicationsPage(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return new ModelAndView("app/cabinet");
    }

    @GetMapping("/app")
    public ModelAndView applicationPage(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return new ModelAndView("app/application");
    }
}
