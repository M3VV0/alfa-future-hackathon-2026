package dev.ammmmm.hackathon.smartcreditor.backend;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.UUID;

@SpringBootTest(properties = {"app.demo-data.enabled=false", "credit.application.worker.enabled=false"})
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackendApplicationTests {
    private static final String SCHEMA = "startup_test_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @AfterAll static void cleanup(@Autowired JdbcTemplate jdbc) { jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE"); }
    @Test void contextLoads() {}
}
