package dev.ammmmm.hackathon.smartcreditor.backend;

import dev.ammmmm.hackathon.smartcreditor.backend.bootstrap.DevelopmentDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentDataInitializerProfileTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=")
            .withUserConfiguration(DevelopmentDataInitializer.class);

    @Test
    void demoAccountsCannotBeSeededOutsideDevOrWithProdActive() {
        for (String[] profiles : new String[][]{{}, {"prod"}, {"dev", "prod"}}) {
            runner.withPropertyValues("app.demo-data.enabled=true")
                    .withInitializer(context ->
                            context.getEnvironment().setActiveProfiles(profiles))
                    .run(context ->
                            assertThat(context).hasNotFailed().doesNotHaveBean(DevelopmentDataInitializer.class));
        }
    }

    @Test
    void demoAccountsCanBeDisabledInDev() {
        runner.withPropertyValues("app.demo-data.enabled=false")
                .withInitializer(context ->
                        context.getEnvironment().setActiveProfiles("dev"))
                .run(context ->
                        assertThat(context).hasNotFailed().doesNotHaveBean(DevelopmentDataInitializer.class));
    }
}
