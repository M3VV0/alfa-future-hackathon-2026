package dev.ammmmm.hackathon.smartcreditor.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@NullMarked
@Configuration
public class LocaleConfig implements WebMvcConfigurer {
    private static final Set<Locale> SUPPORTED_LOCALES = Set.of(
            Locale.forLanguageTag("ru"), Locale.forLanguageTag("en"));

    private final Locale defaultLocale;

    public LocaleConfig(Environment environment) {
        Locale defaultLocale = environment.getRequiredProperty("app.ui.default-locale", Locale.class);
        if (!SUPPORTED_LOCALES.contains(defaultLocale)) {
            throw new IllegalArgumentException("Unsupported default locale: " + defaultLocale);
        }
        this.defaultLocale = defaultLocale;
    }

    @Bean
    MessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(defaultLocale);
        return source;
    }

    @Bean
    LocaleResolver localeResolver(@Value("${security.auth.secure-cookies:true}") boolean secureCookies) {
        var cookies = new CookieLocaleResolver("app-locale");
        cookies.setDefaultLocale(defaultLocale);
        cookies.setRejectInvalidCookies(false);
        cookies.setCookieMaxAge(Duration.ofDays(365));
        cookies.setCookieHttpOnly(true);
        cookies.setCookieSecure(secureCookies);
        return new LocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                return supportedOrDefault(cookies.resolveLocale(request));
            }

            @Override
            public void setLocale(HttpServletRequest request, @Nullable HttpServletResponse response,
                                  @Nullable Locale locale) {
                cookies.setLocale(request, response, supportedOrDefault(locale));
            }
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        var interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        interceptor.setHttpMethods("GET");
        interceptor.setIgnoreInvalidLocale(true);
        registry.addInterceptor(interceptor).addPathPatterns("/login", "/app");
    }

    private Locale supportedOrDefault(@Nullable Locale locale) {
        return locale != null && SUPPORTED_LOCALES.contains(locale) ? locale : defaultLocale;
    }
}
