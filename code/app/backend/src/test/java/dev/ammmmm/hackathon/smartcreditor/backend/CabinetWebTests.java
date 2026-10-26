package dev.ammmmm.hackathon.smartcreditor.backend;

import dev.ammmmm.hackathon.smartcreditor.backend.config.LocaleConfig;
import dev.ammmmm.hackathon.smartcreditor.backend.config.SecurityConfig;
import dev.ammmmm.hackathon.smartcreditor.backend.controller.AuthController;
import dev.ammmmm.hackathon.smartcreditor.backend.controller.CabinetController;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.AuthSessionRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.service.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AuthController.class, CabinetController.class})
@Import({SecurityConfig.class, LocaleConfig.class})
@ActiveProfiles("dev")
class CabinetWebTests {
    @Autowired
    MockMvc mvc;

    @Autowired
    MessageSource messages;

    @Value("${app.ui.default-locale}")
    Locale defaultLocale;

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
    void csrfEndpointReturnsUsableTokenWithoutAccessAuthentication() throws Exception {
        var response = mvc.perform(get("/auth/csrf").cookie(new Cookie("access_token", "expired")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", true));
        var result = response.andReturn();
        var csrfCookie = Objects.requireNonNull(result.getResponse().getCookie("XSRF-TOKEN"));
        var csrf = (CsrfToken) Objects.requireNonNull(
                result.getRequest().getAttribute(CsrfToken.class.getName()));
        response.andExpect(jsonPath("$.headerName").value(csrf.getHeaderName()))
                .andExpect(jsonPath("$.parameterName").value(csrf.getParameterName()))
                .andExpect(jsonPath("$.token").value(csrf.getToken()));

        mvc.perform(post("/logout").cookie(csrfCookie))
                .andExpect(status().isForbidden());
        mvc.perform(post("/logout").cookie(csrfCookie)
                        .header(csrf.getHeaderName(), csrf.getToken()))
                .andExpect(status().isSeeOther());
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
                .andExpect(content().string(containsString("action=\"/api/credit/applications\"")))
                .andExpect(content().string(containsString("data-csrf-url=\"/auth/csrf\"")))
                .andExpect(content().string(
                        matchesPattern("(?s).*action=\"/logout\".*name=\"_csrf\" value=\"[^\"]+\".*")));
    }

    @Test
    void listDetailAndCompanyPagesAreAuthenticatedServerRenderedViews() throws Exception {
        for (String path : List.of("/app/applications", "/app/applications/10000000-0000-4000-8000-000000000001", "/app/companies")) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
            mvc.perform(get(path).with(user("owner"))).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(containsString("/assets/cabinet.js")));
        }
    }

    @Test
    void languageChoiceIsStoredAndUsedOnBothPages() throws Exception {
        for (String language : List.of("ru", "en")) {
            Locale locale = Locale.forLanguageTag(language);
            String expectedLang = "lang=\"" + language + "\"";
            mvc.perform(get("/login").param("lang", language))
                    .andExpect(status().isOk())
                    .andExpect(cookie().value("app-locale", language))
                    .andExpect(content().string(containsString(expectedLang)))
                    .andExpect(content().string(containsString(
                            messages.getMessage("login.welcome", null, locale))));
            mvc.perform(get("/app")
                            .with(user("owner")).cookie(new Cookie("app-locale", language)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(expectedLang)))
                    .andExpect(content().string(containsString(
                            messages.getMessage("application.amount", null, locale))));
        }
    }

    @Test
    void configuredDefaultLocaleIsUsedForMissingOrUnsupportedLanguage() throws Exception {
        String expectedLang = "lang=\"" + defaultLocale.toLanguageTag() + "\"";
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectedLang)));
        mvc.perform(get("/login").param("lang", "de"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("app-locale", defaultLocale.toLanguageTag()))
                .andExpect(content().string(containsString(expectedLang)));
        mvc.perform(get("/login").cookie(new Cookie("app-locale", "de")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectedLang)));
    }

    @Test
    void addingPublicAssetsDoesNotExposeCabinetOrDisableCsrf() throws Exception {
        mvc.perform(post("/logout")).andExpect(status().isForbidden());
        mvc.perform(post("/assets/application.js")).andExpect(status().isForbidden());
    }
}
