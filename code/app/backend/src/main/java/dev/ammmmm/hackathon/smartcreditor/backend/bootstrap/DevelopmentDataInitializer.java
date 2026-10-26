package dev.ammmmm.hackathon.smartcreditor.backend.bootstrap;

import dev.ammmmm.hackathon.smartcreditor.backend.model.Business;
import dev.ammmmm.hackathon.smartcreditor.backend.model.BusinessAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "app.demo-data.enabled", havingValue = "true")
public class DevelopmentDataInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevelopmentDataInitializer.class);
    private static final String DEMO_PASSWORD = "DemoCredit2026!";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public DevelopmentDataInitializer(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UUID firstCompany = ensureBusiness("10000000-0000-4000-8000-000000000001",
                Business.Type.LEGAL_ENTITY, "7700000000", "ООО «Демо Оборот»");
        UUID secondCompany = ensureBusiness("10000000-0000-4000-8000-000000000002",
                Business.Type.INDIVIDUAL_ENTREPRENEUR, "770000000000", "ИП Демо Развитие");

        ensureMembership("30000000-0000-4000-8000-000000000001",
                ensureUser("20000000-0000-4000-8000-000000000001", "owner@demo.test"),
                firstCompany, BusinessAccess.Role.OWNER);
        ensureMembership("30000000-0000-4000-8000-000000000002",
                ensureUser("20000000-0000-4000-8000-000000000002", "admin@demo.test"),
                firstCompany, BusinessAccess.Role.ADMIN);
        ensureMembership("30000000-0000-4000-8000-000000000003",
                ensureUser("20000000-0000-4000-8000-000000000003", "viewer@demo.test"),
                firstCompany, BusinessAccess.Role.VIEWER);
        ensureMembership("30000000-0000-4000-8000-000000000004",
                ensureUser("20000000-0000-4000-8000-000000000004", "other@demo.test"),
                secondCompany, BusinessAccess.Role.OWNER);

        log.info("Development demo accounts are available. Credentials and company UUIDs: README.md");
    }

    private UUID ensureBusiness(String id, Business.Type type, String taxId, String name) {
        jdbc.update("""
                INSERT INTO "Business" ("id", "type", "taxId", "registeredName", "registeredDate", "registrationStatus",
                    "mspCategory", "mspAverageHeadcount", "ogrn", "mspIncludedOn", "mspReportYear")
                VALUES (?, ?, ?, ?, DATE '2018-01-15', 'ACTIVE', 'MICRO', 10, ?, DATE '2018-02-01', 2025)
                ON CONFLICT ("taxId") DO NOTHING
                """, UUID.fromString(id), type.name(), taxId, name,
                type == Business.Type.LEGAL_ENTITY ? "1187700000001" : "318770000000001");
        return jdbc.queryForObject("SELECT \"id\" FROM \"Business\" WHERE \"taxId\" = ?", UUID.class, taxId);
    }

    private UUID ensureUser(String id, String email) {
        var existing = jdbc.queryForList("SELECT \"id\" FROM \"User\" WHERE \"email\" = ?", UUID.class, email);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        jdbc.update("""
                INSERT INTO "User" ("id", "email", "passwordHash")
                VALUES (?, ?, ?)
                ON CONFLICT ("email") DO NOTHING
                """, UUID.fromString(id), email, passwords.encode(DEMO_PASSWORD));
        return jdbc.queryForObject("SELECT \"id\" FROM \"User\" WHERE \"email\" = ?", UUID.class, email);
    }

    private void ensureMembership(String id, UUID userId, UUID businessId, BusinessAccess.Role role) {
        jdbc.update("""
                INSERT INTO "BusinessAccess" ("id", "userId", "businessId", "role")
                VALUES (?, ?, ?, ?)
                ON CONFLICT ("userId", "businessId") DO NOTHING
                """, UUID.fromString(id), userId, businessId, role.name());
    }
}
