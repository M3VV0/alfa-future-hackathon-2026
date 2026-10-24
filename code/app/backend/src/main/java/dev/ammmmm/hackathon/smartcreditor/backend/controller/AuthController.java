package dev.ammmmm.hackathon.smartcreditor.backend.controller;

import dev.ammmmm.hackathon.smartcreditor.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

@NullMarked
@Controller
public class AuthController {
    private final AuthService authService;
    private final CsrfTokenRepository csrfTokens;
    private final boolean secureCookies;

    public AuthController(AuthService authService, CsrfTokenRepository csrfTokens,
                          @Value("${security.auth.secure-cookies:true}") boolean secureCookies) {
        this.authService = authService;
        this.csrfTokens = csrfTokens;
        this.secureCookies = secureCookies;
    }

    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping(value = "/auth/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public CsrfToken csrf(CsrfToken csrfToken) {
        return csrfToken;
    }

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ModelAndView login(
            @RequestParam("email") @NotBlank @Email @Size(max = 255) String email,
            @RequestParam("password") @NotBlank String password,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            var tokens = authService.login(email, password);
            Instant now = Instant.now();
            writeCookie(response, "access_token", tokens.accessToken(),
                    Duration.between(now, tokens.accessExpiresAt()));
            writeCookie(response, "refresh_token", tokens.refreshToken(),
                    Duration.between(now, tokens.sessionExpiresAt()));
        } catch (BadCredentialsException ex) {
            return loginError(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        csrfTokens.saveToken(null, request, response);
        var redirect = new RedirectView("/app", true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        redirect.setExposeModelAttributes(false);
        return new ModelAndView(redirect);
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<Void> refresh(@CookieValue(name = "refresh_token", required = false)
                                        @Nullable String refreshToken, HttpServletResponse response) {
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            var tokens = authService.refresh(refreshToken);
            Instant now = Instant.now();
            writeCookie(response, "access_token", tokens.accessToken(),
                    Duration.between(now, tokens.accessExpiresAt()));
            writeCookie(response, "refresh_token", tokens.refreshToken(),
                    Duration.between(now, tokens.sessionExpiresAt()));
        } catch (BadCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token", required = false)
            @Nullable String refreshToken, HttpServletRequest request, HttpServletResponse response) {
        authService.logout(refreshToken);
        writeCookie(response, "access_token", "", Duration.ZERO);
        writeCookie(response, "refresh_token", "", Duration.ZERO);
        csrfTokens.saveToken(null, request, response);

        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/login")).build();
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, HandlerMethodValidationException.class})
    public ModelAndView invalidLoginForm() {
        return loginError(HttpStatus.UNPROCESSABLE_CONTENT,
                "Enter a valid email address and a non-empty password");
    }

    private void writeCookie(HttpServletResponse response, String name,
                             String value, Duration ttl) {
        var cookie = ResponseCookie.from(name, value)
                .httpOnly(true).secure(secureCookies).sameSite("Lax").path("/")
                .maxAge(Math.max(0, ttl.getSeconds())).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private ModelAndView loginError(HttpStatus status, String message) {
        var view = new ModelAndView("auth/login");
        view.setStatus(status);
        view.addObject("loginError", message);
        return view;
    }
}
