package ru.alfa.credit.core;

import static ru.alfa.credit.core.Application.State.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.alfa.credit.platform.*;

@Service
@Transactional
public class Applications {
  @PersistenceContext EntityManager em;
  private final JdbcTemplate db;
  private final Json json;
  private final Access access;
  private final Commands commands;
  private final Jobs jobs;
  private final Validator validator;

  public Applications(
      JdbcTemplate db,
      Json json,
      Access access,
      Commands commands,
      Jobs jobs,
      Validator validator) {
    this.db = db;
    this.json = json;
    this.access = access;
    this.commands = commands;
    this.jobs = jobs;
    this.validator = validator;
  }

  public UUID create(UUID user, UUID company, String product, Draft draft, UUID command) {
    access.company(user, company);
    valid(draft);
    return commands.run(
        user,
        command,
        Arrays.asList("create", company, product, draft),
        () -> {
          var versions =
              db.queryForList(
                  "select version from core.product where id=? order by version desc limit 1",
                  product);
          if (versions.isEmpty()) throw Problem.missing();
          documents(user, company, draft);
          Application app = new Application();
          app.id = UUID.randomUUID();
          app.companyId = company;
          app.applicantId = user;
          app.productId = product;
          app.productVersion = ((Number) versions.getFirst().get("version")).intValue();
          app.status = DRAFT;
          app.draft = json.tree(draft);
          em.persist(app);
          em.flush();
          event(app, null, "DRAFT_CREATED", "USER", user.toString());
          access.audit(user, "APPLICATION_CREATE", app.id);
          return app.id;
        });
  }

  public UUID save(UUID user, UUID id, long version, Draft draft, UUID command) {
    Application app = owned(user, id);
    valid(draft);
    return commands.run(
        user,
        command,
        Arrays.asList("save", id, version, draft),
        () -> {
          expected(app, version);
          require(app, DRAFT, NEEDS_INFO);
          documents(user, app.companyId, draft);
          app.draft = json.tree(draft);
          app.updatedAt = Instant.now();
          em.flush();
          access.audit(user, "APPLICATION_SAVE", id);
          return id;
        });
  }

  public UUID submit(UUID user, UUID id, long version, UUID command, boolean resubmit) {
    Application app = owned(user, id);
    return commands.run(
        user,
        command,
        List.of(resubmit ? "resubmit" : "submit", id, version),
        () -> {
          expected(app, version);
          require(app, resubmit ? NEEDS_INFO : DRAFT);
          Draft draft = json.parse(app.draft.toString(), Draft.class);
          valid(draft);
          documents(user, app.companyId, draft);
          var company =
              db.queryForMap(
                  "select id,inn,name,registered_at,profile,version from core.company where id=?",
                  app.companyId);
          // Convert JDBC values to immutable JSON with stable ISO dates.
          Map<String, Object> profile = new LinkedHashMap<>();
          company.forEach((k, v) -> profile.put(k, v == null ? null : v.toString()));
          JsonNode snapshot =
              json.tree(
                  Map.of(
                      "currency",
                      "RUB",
                      "asOf",
                      java.time.LocalDate.now().toString(),
                      "draft",
                      draft,
                      "company",
                      profile,
                      "product",
                      db.queryForMap(
                          "select * from core.product where id=? and version=?",
                          app.productId,
                          app.productVersion)));
          app.currentRevision++;
          db.update(
              "insert into core.application_revision(application_id,revision,snapshot,payload_hash)"
                  + " values (?,?,?::jsonb,?)",
              id,
              app.currentRevision,
              json.write(snapshot),
              json.hash(snapshot));
          for (UUID doc : new HashSet<>(draft.documentIds()))
            db.update(
                "insert into core.revision_document values (?,?,?)", id, app.currentRevision, doc);
          if (resubmit)
            db.update(
                "update core.info_request set answered_at=now() where application_id=? and"
                    + " answered_at is null",
                id);
          transition(app, SUBMITTED, "APPLICATION_SUBMITTED", "USER", user.toString());
          app.processingState = "RUNNING";
          em.flush();
          jobs.enqueue("VALIDATE", id + ":" + app.currentRevision + ":validate", payload(app));
          access.audit(user, "APPLICATION_SUBMIT", id);
          return id;
        });
  }

  public UUID cancel(UUID user, UUID id, long version, UUID command) {
    Application app = owned(user, id);
    return commands.run(
        user,
        command,
        List.of("cancel", id, version),
        () -> {
          expected(app, version);
          require(app, DRAFT, NEEDS_INFO);
          transition(app, CANCELLED, "CLIENT_CANCELLED", "USER", user.toString());
          app.processingState = "IDLE";
          return id;
        });
  }

  public Map<String, Object> detail(UUID user, UUID id) {
    Application app = owned(user, id);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", app.id);
    result.put("companyId", app.companyId);
    result.put("productId", app.productId);
    result.put("status", app.status);
    result.put("processingState", app.processingState);
    result.put("version", app.version);
    result.put("revision", app.currentRevision);
    result.put("draft", app.draft);
    result.put(
        "nextAction",
        app.processingState.equals("FAILED")
            ? "Техническая задержка: требуется повтор обработки"
            : app.processingState.equals("RETRY_WAIT")
                ? "Временная задержка: обработка будет повторена автоматически"
                : switch (app.status) {
                  case DRAFT -> "Заполните и отправьте заявку";
                  case NEEDS_INFO -> "Дополните сведения и отправьте повторно";
                  case REJECTED -> "Отказ по заявке; причины доступны в решении";
                  case OFFERED -> "Ознакомьтесь с условиями и примите или отклоните предложение";
                  case ACCEPTED -> "Подпишите договор в режиме имитации";
                  case SIGNED_SIMULATED -> "Подтвердите имитацию выдачи";
                  case DISBURSED_SIMULATED ->
                      "Выдача имитирована; реальное движение средств отсутствует";
                  case CANCELLED, DECLINED, EXPIRED ->
                      "Обработка завершена; при необходимости создайте новую заявку";
                  default -> "Ожидайте завершения текущего этапа";
                });
    result.put(
        "history",
        db.queryForList("select * from core.status_event where application_id=? order by id", id));
    result.put(
        "infoRequests",
        db.queryForList(
            "select * from core.info_request where application_id=? order by created_at", id));
    result.put(
        "decisions",
        db.queryForList(
            "select * from core.decision where application_id=? order by revision", id));
    result.put(
        "decisionExplanations",
        db
            .queryForList(
                "select reasons::text from core.decision where application_id=? order by revision",
                String.class,
                id)
            .stream()
            .flatMap(
                reasons -> {
                  List<String> texts = new ArrayList<>();
                  for (JsonNode code : json.read(reasons))
                    texts.add(ReasonText.explain(code.asText()));
                  return texts.stream();
                })
            .toList());
    result.put("offers", db.queryForList("select * from core.offer where application_id=?", id));
    return result;
  }

  public List<Map<String, Object>> list(UUID user) {
    return db.queryForList(
        "select a.* from core.application a join auth.membership m on m.company_id=a.company_id"
            + " where m.user_id=? order by a.created_at desc limit 200",
        user);
  }

  Application owned(UUID user, UUID id) {
    Application app = load(id);
    access.company(user, app.companyId);
    return app;
  }

  Application load(UUID id) {
    Application app = em.find(Application.class, id, LockModeType.PESSIMISTIC_WRITE);
    if (app == null) throw Problem.missing();
    return app;
  }

  void expected(Application app, long version) {
    if (app.version != version) throw Problem.conflict("STALE_VERSION");
  }

  void require(Application app, Application.State... states) {
    if (!Arrays.asList(states).contains(app.status)) throw Problem.conflict("INVALID_TRANSITION");
  }

  void transition(
      Application app, Application.State target, String reason, String actorType, String actor) {
    Application.State from = app.status;
    if (!StateTransitions.allowed(from, target)) throw Problem.conflict("INVALID_TRANSITION");
    app.status = target;
    app.updatedAt = Instant.now();
    event(app, from, reason, actorType, actor);
  }

  private void event(
      Application app, Application.State from, String reason, String actorType, String actor) {
    db.update(
        "insert into"
            + " core.status_event(application_id,revision,from_status,to_status,reason,actor_type,actor_id,trace_id)"
            + " values (?,?,?,?,?,?,?,?)",
        app.id,
        app.currentRevision,
        from == null ? null : from.name(),
        app.status.name(),
        reason,
        actorType,
        actor,
        TraceFilter.current());
  }

  Map<String, Object> payload(Application app) {
    return Map.of("applicationId", app.id, "revision", app.currentRevision);
  }

  private void valid(Draft draft) {
    if (draft == null || !validator.validate(draft).isEmpty())
      throw Problem.invalid("INVALID_DRAFT");
    if (draft.period() != null
        && java.time.YearMonth.parse(draft.period()).isAfter(java.time.YearMonth.now()))
      throw Problem.invalid("FUTURE_FINANCIAL_PERIOD");
  }

  private void documents(UUID user, UUID company, Draft draft) {
    for (UUID id : draft.documentIds()) {
      var found = db.queryForList("select company_id,state from core.document where id=?", id);
      if (found.isEmpty()
          || !company.equals(found.getFirst().get("company_id"))
          || !"READY".equals(found.getFirst().get("state")))
        throw Problem.invalid("DOCUMENT_NOT_AVAILABLE");
    }
  }
}
