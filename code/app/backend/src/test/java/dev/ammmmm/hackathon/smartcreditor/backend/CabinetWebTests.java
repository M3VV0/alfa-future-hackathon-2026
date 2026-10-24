package dev.ammmmm.hackathon.smartcreditor.backend;

import dev.ammmmm.hackathon.smartcreditor.backend.config.SecurityConfig;
import dev.ammmmm.hackathon.smartcreditor.backend.controller.AuthController;
import dev.ammmmm.hackathon.smartcreditor.backend.controller.CabinetController;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.AuthSessionRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AuthController.class, CabinetController.class})
@Import(SecurityConfig.class)
@ActiveProfiles("dev")
class CabinetWebTests {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthService authService;

    @MockitoBean
    AuthSessionRepository sessions;

    @Test
    void loginRendersCsrfProtectedFormAndPublicStyles() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(matchesPattern("(?s).*name=\"_csrf\" value=\"[^\"]+\".*")))
                .andExpect(content().string(containsString("autocomplete=\"current-password\"")));
        mvc.perform(get("/assets/cabinet.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".application-workspace")));
        mvc.perform(get("/assets/application.js")).andExpect(status().isOk());
    }

    @Test
    void cabinetRequiresAuthentication() throws Exception {
        mvc.perform(get("/app"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void cabinetRendersExistingSubmissionContractAndCsrf() throws Exception {
        mvc.perform(get("/app").with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(matchesPattern("(?s).*name=\"csrf-token\" content=\"[^\"]+\".*")))
                .andExpect(content().string(containsString("name=\"csrf-header\" content=\"X-XSRF-TOKEN\"")))
                .andExpect(content().string(containsString("action=\"/api/credit/application/submit\"")))
                .andExpect(content().string(matchesPattern("(?s).*action=\"/logout\".*name=\"_csrf\" value=\"[^\"]+\".*")));
    }

    @Test
    void addingPublicAssetsDoesNotExposeCabinetOrDisableCsrf() throws Exception {
        mvc.perform(post("/logout")).andExpect(status().isForbidden());
        mvc.perform(post("/assets/application.js")).andExpect(status().isForbidden());
    }
}
