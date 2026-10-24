package dev.ammmmm.hackathon.smartcreditor.backend;

import dev.ammmmm.hackathon.smartcreditor.backend.config.DevDemoData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DevDemoDataProfileTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=")
            .withUserConfiguration(DevDemoData.class);

    @Test
    void demoAccountsCannotBeSeededOutsideDevOrWithProdActive() {
        for (String[] profiles : new String[][]{{}, {"prod"}, {"dev", "prod"}}) {
            runner.withPropertyValues("app.demo-data.enabled=true")
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DevDemoData.class));
        }
    }

    @Test
    void demoAccountsCanBeDisabledInDev() {
        runner.withPropertyValues("app.demo-data.enabled=false")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DevDemoData.class));
    }
}
