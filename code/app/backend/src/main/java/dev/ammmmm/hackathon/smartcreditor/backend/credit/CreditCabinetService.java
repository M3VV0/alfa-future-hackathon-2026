package dev.ammmmm.hackathon.smartcreditor.backend.credit;

import dev.ammmmm.hackathon.smartcreditor.backend.client.ScorerClient;
import dev.ammmmm.hackathon.smartcreditor.backend.model.*;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.*;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Every mutation locks the queue row first, also used by dispatch and callback. */
@Service
public class CreditCabinetService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Validator validator;
    private final CreditApplicationRepository applications;
    private final CreditApplicationTaskRepository tasks;
    private final BusinessRepository businesses;
    private final BusinessAccessRepository access;
    private final CreditDecisionPolicy policy;
    private final boolean scoringEnabled;

    public CreditCabinetService(JdbcTemplate jdbc, ObjectMapper json, Validator validator,
                               CreditApplicationRepository applications, CreditApplicationTaskRepository tasks,
                               BusinessRepository businesses, BusinessAccessRepository access,
                               CreditDecisionPolicy policy, @Value("${credit.application.worker.enabled:false}") boolean scoringEnabled) {
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
        this.applications = applications;
        this.tasks = tasks;
        this.businesses = businesses;
        this.access = access;
        this.policy = policy;
        this.scoringEnabled = scoringEnabled;
    }

    public record CompanyInput(@NotBlank @jakarta.validation.constraints.Size(max = 255) String name,
            @NotNull @jakarta.validation.constraints.PastOrPresent LocalDate registeredDate,
            @NotNull Business.RegistrationStatus registrationStatus) {}

    @Transactional
    public void updateCompany(UUID user, UUID id, CompanyInput input) {
        requireAccess(user, id, true);
        jdbc.queryForObject("SELECT \"id\" FROM \"Business\" WHERE \"id\" = ? FOR UPDATE", UUID.class, id);
        var business = businesses.findById(id).orElseThrow();
        business.setRegisteredName(input.name().trim());
        business.setRegisteredDate(input.registeredDate());
        business.setRegistrationStatus(input.registrationStatus());
    }

    public record Report(@NotBlank String inn, @NotNull @Valid Financials financials) {}
    private record Dossier(UUID id, String productId, String state, int revision, Financials financials,
                           CreditDecisionPolicy.Decision decision, String reasonCode) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> companies(UUID user) {
        return jdbc.query("""
                SELECT b."id", b."registeredName", b."taxId", b."type", b."registeredDate", b."registrationStatus", a."role"
                FROM "Business" b JOIN "BusinessAccess" a ON a."businessId" = b."id"
                WHERE a."userId" = ? ORDER BY b."registeredName", b."id"
                """, (row, index) -> fields("id", row.getObject("id"), "name", row.getString("registeredName"),
                "inn", row.getString("taxId"), "type", row.getString("type"), "registeredDate", row.getString("registeredDate"),
                "registrationStatus", row.getString("registrationStatus"), "role", row.getString("role")), user);
    }

    @Transactional
    public UUID create(UUID user, UUID key, CabinetInput input) {
        requireAccess(user, input.application().businessId(), true);
        validateProduct(input);
        // Serialize same-user creates; the DB unique constraint is a second line of defence.
        jdbc.queryForObject("SELECT \"id\" FROM \"User\" WHERE \"id\" = ? FOR UPDATE", UUID.class, user);
        String fingerprint = fingerprint(input);
        var previous = jdbc.query("""
                SELECT "applicationId", "inputHash" FROM "CreditDossier" WHERE "creatorId" = ? AND "idempotencyKey" = ?
                """, (row, n) -> fields("id", row.getObject(1), "hash", row.getString(2)), user, key);
        if (!previous.isEmpty()) {
            if (!fingerprint.equals(previous.getFirst().get("hash"))) throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            return (UUID) previous.getFirst().get("id");
        }
        var application = applications.saveAndFlush(new CreditApplication(user, input.application()));
        var task = new CreditApplicationTask(application.getId(), application.getCreationTime());
        task.pause();
        tasks.saveAndFlush(task);
        jdbc.update("""
                INSERT INTO "CreditDossier" ("applicationId","creatorId","idempotencyKey","inputHash","productId",
                    "state","financials","reasonCode") VALUES (?, ?, ?, ?, ?, 'DRAFT', ?::jsonb, 'DRAFT_CREATED')
                """, application.getId(), user, key, fingerprint, input.productId(), encode(input.financials()));
        event(application.getId(), "DRAFT", "DRAFT_CREATED", input);
        return application.getId();
    }

    @Transactional
    public void edit(UUID user, UUID id, CabinetInput input) {
        var application = owned(user, id, true);
        lock(id);
        var dossier = dossier(id);
        requireEditable(dossier);
        if (!application.getRequest().businessId().equals(input.application().businessId())) throw problem(HttpStatus.CONFLICT, "COMPANY_IMMUTABLE");
        validateProduct(input);
        application.replaceDraftRequest(input.application());
        applications.flush();
        jdbc.update("""
                UPDATE "CreditDossier" SET "productId" = ?, "financials" = ?::jsonb, "revision" = "revision" + 1,
                    "state" = 'DRAFT', "reasonCode" = 'DRAFT_UPDATED' WHERE "applicationId" = ?
                """, input.productId(), encode(input.financials()), id);
        event(id, "DRAFT", "DRAFT_UPDATED", input);
    }

    @Transactional
    public void submit(UUID user, UUID id) {
        var application = owned(user, id, true);
        var task = lock(id);
        var dossier = dossier(id);
        if (Set.of("SUBMITTED", "SCORING").contains(dossier.state())) return;
        requireEditable(dossier);
        var business = businesses.findById(application.getRequest().businessId()).orElseThrow();
        var product = CreditProduct.find(dossier.productId());
        String missing = missingInformation(dossier, business);
        if (missing != null) {
            state(id, "NEEDS_INFO", missing);
            return;
        }
        LocalDate submissionDate = LocalDate.now(ZoneOffset.UTC);
        Map<String, Object> payload = ScorerClient.createPayload(application, business);
        @SuppressWarnings("unchecked") var parameters = (Map<String, Object>) payload.get("parameters");
        parameters.put("snapshot_on", submissionDate.toString());
        parameters.put("product_id", product.id());
        parameters.put("financials", fields("monthlyRevenue", dossier.financials().monthlyRevenue().toPlainString(),
                "monthlyExpenses", dossier.financials().monthlyExpenses().toPlainString(),
                "existingMonthlyPayment", dossier.financials().existingMonthlyPayment().toPlainString()));
        parameters.put("input_revision", dossier.revision());
        // Keep the whole confirmed input before making any asynchronous calls.
        jdbc.update("UPDATE \"CreditDossier\" SET \"submissionSnapshot\" = ?::jsonb WHERE \"applicationId\" = ?",
                encode(fields("scorerRequest", payload, "registrationStatus", business.getRegistrationStatus(),
                        "registeredDate", business.getRegisteredDate(), "submittedAt", Instant.now(), "productVersion", product.version(),
                        "policyVersion", CreditDecisionPolicy.VERSION,
                        "report", jdbc.queryForMap("SELECT \"id\", \"revision\", \"checksum\" FROM \"CreditDocument\" WHERE \"applicationId\" = ? ORDER BY \"revision\" DESC LIMIT 1", id))), id);
        if (business.getRegistrationStatus() != Business.RegistrationStatus.ACTIVE) {
            storeDecision(id, policy.rejected(product, "BUSINESS_NOT_ACTIVE"));
            task.complete(Instant.now());
            application.markCompletedWithoutScoring();
            return;
        }
        if (business.getRegisteredDate().plusMonths(product.minBusinessAgeMonths())
                .isAfter(submissionDate)) {
            storeDecision(id, policy.rejected(product, "BUSINESS_TOO_YOUNG"));
            task.complete(Instant.now());
            application.markCompletedWithoutScoring();
            return;
        }
        task.setRequestPayload(payload);
        task.retry(Instant.now(), Instant.now(), null);
        state(id, "SUBMITTED", "AWAITING_SCORER");
    }

    private String missingInformation(Dossier dossier, Business business) {
        if (business.getRegisteredDate() == null || business.getRegistrationStatus() == null) return "COMPANY_DATA_REQUIRED";
        if (dossier.financials() == null) return "FINANCIALS_REQUIRED";
        var reports = jdbc.query("""
                SELECT "content" FROM "CreditDocument" WHERE "applicationId" = ? ORDER BY "revision" DESC LIMIT 1
                """, (row, index) -> row.getBytes(1), dossier.id());
        if (reports.isEmpty()) return "FINANCIAL_REPORT_REQUIRED";
        Report report = json.readValue(reports.getFirst(), Report.class);
        if (!business.getTaxId().equals(report.inn()) || !dossier.financials().sameValues(report.financials())) return "REPORT_MISMATCH";
        return null;
    }

    @Transactional
    public UUID upload(UUID user, UUID id, byte[] content) {
        owned(user, id, true);
        lock(id);
        var dossier = dossier(id);
        requireEditable(dossier);
        if (content.length == 0 || content.length > 1048576) throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "REPORT_MAX_1_MB");
        Report report;
        try {
            report = json.readValue(content, Report.class);
            if (report == null || !validator.validate(report).isEmpty()) throw new IllegalArgumentException();
        } catch (RuntimeException error) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_REPORT_JSON");
        }
        UUID document = UUID.randomUUID();
        jdbc.update("UPDATE \"CreditDossier\" SET \"revision\" = \"revision\" + 1 WHERE \"applicationId\" = ?", id);
        jdbc.update("""
                INSERT INTO "CreditDocument" ("id","applicationId","revision","content","checksum") VALUES (?, ?, ?, ?, ?)
                """, document, id, dossier.revision() + 1, content, sha(content));
        event(id, dossier.state(), "REPORT_UPLOADED", fields("documentId", document, "checksum", sha(content)));
        return document;
    }

    @Transactional(readOnly = true)
    public byte[] download(UUID user, UUID id, UUID document) {
        owned(user, id, false);
        return jdbc.query("SELECT \"content\" FROM \"CreditDocument\" WHERE \"id\" = ? AND \"applicationId\" = ?",
                (row, index) -> row.getBytes(1), document, id).stream().findFirst()
                .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID user) {
        return jdbc.query("""
                SELECT a."id", a."creationTime", a."requestedAmount", a."termMonths", a."status", b."registeredName",
                    d."productId", d."state", d."reasonCode"
                FROM "CreditApplication" a JOIN "BusinessAccess" ba ON ba."businessId" = a."businessId"
                JOIN "Business" b ON b."id" = a."businessId" LEFT JOIN "CreditDossier" d ON d."applicationId" = a."id"
                WHERE ba."userId" = ? ORDER BY a."creationTime" DESC, a."id" DESC LIMIT 100
                """, (row, index) -> fields("id", row.getObject("id"), "createdAt", row.getTimestamp("creationTime").toInstant(),
                "requestedAmount", row.getBigDecimal("requestedAmount").toPlainString(), "termMonths", row.getInt("termMonths"),
                "company", row.getString("registeredName"), "productId", row.getString("productId"),
                "state", row.getString("state") == null ? row.getString("status") : row.getString("state"),
                "reasonCode", row.getString("reasonCode")), user);
    }

    @Transactional
    public Map<String, Object> view(UUID user, UUID id) {
        var application = owned(user, id, false);
        lock(id);
        var dossiers = dossiers(id);
        var result = fields("id", id, "application", application.getRequest(), "createdAt", application.getCreationTime(),
                "technicalStatus", application.getStatus(), "scoringEnabled", scoringEnabled,
                "canManage", requireAccess(user, application.getRequest().businessId(), false) != BusinessAccess.Role.VIEWER);
        if (dossiers.isEmpty()) {
            result.putAll(fields("state", application.getStatus(), "legacy", true, "scorerResult", application.getScorerResult()));
        } else {
            var dossier = expire(dossiers.getFirst());
            result.putAll(fields("state", dossier.state(), "productId", dossier.productId(), "revision", dossier.revision(),
                    "financials", dossier.financials(), "decision", dossier.decision(), "reasonCode", dossier.reasonCode(), "legacy", false));
        }
        result.put("documents", jdbc.query("""
                SELECT "id", "revision", "checksum", "createdAt" FROM "CreditDocument" WHERE "applicationId" = ? ORDER BY "revision" DESC
                """, (row, n) -> fields("id", row.getObject(1), "revision", row.getInt(2), "checksum", row.getString(3),
                "createdAt", row.getTimestamp(4).toInstant()), id));
        result.put("history", jdbc.query("""
                SELECT "state", "reasonCode", "revision", "createdAt" FROM "CreditEvent" WHERE "applicationId" = ? ORDER BY "sequence"
                """, (row, n) -> fields("state", row.getString(1), "reasonCode", row.getString(2), "revision", row.getInt(3),
                "createdAt", row.getTimestamp(4).toInstant()), id));
        return result;
    }

    @Transactional
    public void action(UUID user, UUID id, String action) {
        var application = owned(user, id, true);
        var task = lock(id);
        var dossier = expire(dossier(id));
        if ("retry".equals(action)) {
            if (Set.of("SUBMITTED", "SCORING").contains(dossier.state())) return;
            if (!"FAILED".equals(dossier.state())) throw problem(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
            task.restart(Instant.now());
            application.markPending();
            state(id, "SUBMITTED", "RETRY_REQUESTED");
            return;
        }
        String target = switch (action) {
            case "accept" -> "ACCEPTED";
            case "decline" -> "DECLINED";
            case "sign" -> "SIGNED_SIMULATED";
            case "disburse" -> "DISBURSED_SIMULATED";
            case "cancel" -> "CANCELLED";
            default -> throw problem(HttpStatus.NOT_FOUND, "UNKNOWN_ACTION");
        };
        // Repeated commands, including after a lost HTTP response, have no extra effects.
        if (dossier.state().equals(target)) return;
        boolean allowed = switch (action) {
            case "accept", "decline" -> dossier.state().equals("OFFERED");
            case "sign" -> dossier.state().equals("ACCEPTED");
            case "disburse" -> dossier.state().equals("SIGNED_SIMULATED");
            case "cancel" -> Set.of("DRAFT", "NEEDS_INFO", "SUBMITTED", "SCORING", "FAILED").contains(dossier.state());
            default -> false;
        };
        if (!allowed) throw problem(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        state(id, target, target);
        if (target.equals("CANCELLED")) {
            task.complete(Instant.now());
            application.markCompletedWithoutScoring();
        }
    }

    /** Caller holds the queue lock in the same transaction. No network/transaction split here. */
    public void scoringStarted(UUID id) {
        jdbc.update("UPDATE \"CreditDossier\" SET \"state\" = 'SCORING', \"reasonCode\" = 'SCORING' WHERE \"applicationId\" = ? AND \"state\" = 'SUBMITTED'", id);
        if (!dossiers(id).isEmpty()) event(id, "SCORING", "SCORING", null);
    }

    public void scoringFailed(UUID id, String code) {
        if (!dossiers(id).isEmpty()) state(id, "FAILED", code);
    }

    public void scoringCompleted(CreditApplication application, ScorerResult result, Instant now) {
        var found = dossiers(application.getId());
        if (found.isEmpty()) return; // Existing pre-MVP submissions retain their original contract.
        var dossier = found.getFirst();
        if (!Set.of("SUBMITTED", "SCORING", "FAILED").contains(dossier.state())) throw problem(HttpStatus.CONFLICT, "APPLICATION_NOT_SCORING");
        var decision = policy.decide(CreditProduct.find(dossier.productId()), application.getRequest(), dossier.financials(), result, now);
        storeDecision(application.getId(), decision);
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void expireOffers() {
        var expired = jdbc.query("""
                SELECT d."applicationId" FROM "CreditDossier" d
                JOIN "CreditApplicationTask" t ON t."applicationId" = d."applicationId"
                WHERE d."state" = 'OFFERED' AND (d."decision"->>'expiresAt')::timestamptz <= CURRENT_TIMESTAMP
                ORDER BY d."applicationId" LIMIT 100 FOR UPDATE OF t SKIP LOCKED
                """, (row, index) -> row.getObject(1, UUID.class));
        for (UUID id : expired) expire(dossier(id));
    }

    private Dossier expire(Dossier dossier) {
        if (dossier.state().equals("OFFERED") && dossier.decision().expiresAt().isBefore(Instant.now())) {
            state(dossier.id(), "EXPIRED", "OFFER_EXPIRED");
            return dossier(dossier.id());
        }
        return dossier;
    }

    private void storeDecision(UUID id, CreditDecisionPolicy.Decision decision) {
        jdbc.update("UPDATE \"CreditDossier\" SET \"decision\" = ?::jsonb WHERE \"applicationId\" = ?", encode(decision), id);
        state(id, decision.state(), decision.reasonCode());
    }

    private void state(UUID id, String state, String reason) {
        jdbc.update("UPDATE \"CreditDossier\" SET \"state\" = ?, \"reasonCode\" = ? WHERE \"applicationId\" = ?", state, reason, id);
        event(id, state, reason, null);
    }

    private void event(UUID id, String state, String reason, Object snapshot) {
        jdbc.update("""
                INSERT INTO "CreditEvent" ("id","applicationId","revision","state","reasonCode","snapshot")
                SELECT ?, "applicationId", "revision", ?, ?, ?::jsonb FROM "CreditDossier" WHERE "applicationId" = ?
                """, UUID.randomUUID(), state, reason, encode(snapshot), id);
    }

    private CreditApplication owned(UUID user, UUID id, boolean write) {
        if (write) lock(id);
        var application = applications.findById(id).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));
        requireAccess(user, application.getRequest().businessId(), write);
        return application;
    }

    private BusinessAccess.Role requireAccess(UUID user, UUID business, boolean write) {
        var role = access.findByUserIdAndBusinessId(user, business)
                .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND")).getRole();
        if (write && role == BusinessAccess.Role.VIEWER) throw problem(HttpStatus.FORBIDDEN, "WRITE_ACCESS_REQUIRED");
        return role;
    }

    private CreditApplicationTask lock(UUID id) {
        return tasks.findByIdForUpdate(id).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));
    }

    private void requireEditable(Dossier dossier) {
        if (!Set.of("DRAFT", "NEEDS_INFO").contains(dossier.state())) throw problem(HttpStatus.CONFLICT, "APPLICATION_IMMUTABLE");
    }

    private void validateProduct(CabinetInput input) {
        var product = CreditProduct.find(input.productId());
        var request = input.application();
        if (request.requestedAmount().compareTo(product.minAmount()) < 0 || request.requestedAmount().compareTo(product.maxAmount()) > 0
                || request.termMonths() < product.minMonths() || request.termMonths() > product.maxMonths()
                || request.financingPurpose() != product.purpose()
                || (request.paymentFrequency() == CreditApplicationRequest.PaymentFrequency.QUARTERLY && request.termMonths() % 3 != 0)) {
            throw problem(HttpStatus.BAD_REQUEST, "PRODUCT_LIMITS");
        }
    }

    private Dossier dossier(UUID id) {
        return dossiers(id).stream().findFirst().orElseThrow(() -> problem(HttpStatus.CONFLICT, "LEGACY_APPLICATION_READ_ONLY"));
    }

    private List<Dossier> dossiers(UUID id) {
        return jdbc.query("SELECT * FROM \"CreditDossier\" WHERE \"applicationId\" = ?", (row, n) ->
                new Dossier(id, row.getString("productId"), row.getString("state"), row.getInt("revision"),
                        decode(row.getString("financials"), Financials.class),
                        decode(row.getString("decision"), CreditDecisionPolicy.Decision.class), row.getString("reasonCode")), id);
    }

    private String encode(Object value) { return value == null ? null : json.writeValueAsString(value); }
    private <T> T decode(String value, Class<T> type) { return value == null ? null : json.readValue(value, type); }

    private String fingerprint(CabinetInput input) {
        var request = input.application();
        var finance = input.financials();
        String canonical = String.join("|", input.productId(), request.businessId().toString(), money(request.requestedAmount()),
                request.termMonths().toString(), request.paymentFrequency().name(), request.financingPurpose().name(), money(request.declaredCollateralValue()),
                finance == null ? "missing" : String.join("|", money(finance.monthlyRevenue()), money(finance.monthlyExpenses()), money(finance.existingMonthlyPayment())));
        return sha(canonical.getBytes(StandardCharsets.UTF_8));
    }
    private static String money(BigDecimal value) { return value == null ? "missing" : value.stripTrailingZeros().toPlainString(); }
    private static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
    private static ResponseStatusException problem(HttpStatus status, String code) { return new ResponseStatusException(status, code); }
}
