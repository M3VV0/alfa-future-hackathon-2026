package ru.alfa.credit.core;

import static ru.alfa.credit.core.Application.State.*;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.alfa.credit.platform.*;

@Service
@Transactional
public class Offers {
  private final JdbcTemplate db;
  private final Applications apps;
  private final Commands commands;
  private final Access access;

  public Offers(JdbcTemplate db, Applications apps, Commands commands, Access access) {
    this.db = db;
    this.apps = apps;
    this.commands = commands;
    this.access = access;
  }

  public Map<String, Object> offer(UUID user, UUID id) {
    var rows = db.queryForList("select * from core.offer where id=?", id);
    if (rows.isEmpty()) throw Problem.missing();
    var offer = rows.getFirst();
    apps.owned(user, (UUID) offer.get("application_id"));
    return offer;
  }

  public Map<String, Object> contract(UUID user, UUID id) {
    var rows = db.queryForList("select * from core.contract where id=?", id);
    if (rows.isEmpty()) throw Problem.missing();
    var contract = rows.getFirst();
    apps.owned(user, (UUID) contract.get("application_id"));
    return contract;
  }

  public UUID respond(UUID user, UUID id, long version, UUID command, boolean accept) {
    var offer = offer(user, id);
    Application app = apps.owned(user, (UUID) offer.get("application_id"));
    return commands.run(
        user,
        command,
        List.of(accept ? "accept" : "decline", id, version),
        () -> {
          apps.expected(app, version);
          apps.require(app, OFFERED);
          if (!"OPEN".equals(offer.get("status"))) throw Problem.conflict("OFFER_NOT_OPEN");
          if (!((java.sql.Timestamp) offer.get("expires_at")).toInstant().isAfter(Instant.now()))
            throw Problem.conflict("OFFER_EXPIRED");
          db.update(
              "update core.offer set status=? where id=?", accept ? "ACCEPTED" : "DECLINED", id);
          apps.transition(
              app,
              accept ? ACCEPTED : DECLINED,
              accept ? "OFFER_ACCEPTED" : "OFFER_DECLINED",
              "USER",
              user.toString());
          if (accept) {
            UUID contract = UUID.randomUUID();
            String text =
                "СИНТЕТИЧЕСКИЙ ДОГОВОР. ТОЛЬКО ИМИТАЦИЯ, БЕЗ РЕАЛЬНОЙ ВЫДАЧИ.\nПредложение: "
                    + id
                    + "\nСумма в копейках: "
                    + offer.get("amount_minor")
                    + "\nСрок: "
                    + offer.get("term_months")
                    + "\nСтавка: "
                    + offer.get("annual_rate")
                    + "\nПлатёж в копейках: "
                    + offer.get("monthly_payment_minor");
            db.update(
                "insert into core.contract(id,offer_id,application_id,text_content) values"
                    + " (?,?,?,?)",
                contract,
                id,
                app.id,
                text);
          }
          access.audit(user, accept ? "OFFER_ACCEPT" : "OFFER_DECLINE", id);
          return app.id;
        });
  }

  public UUID simulate(UUID user, UUID id, long version, UUID command, boolean disburse) {
    var contract = contract(user, id);
    Application app = apps.owned(user, (UUID) contract.get("application_id"));
    return commands.run(
        user,
        command,
        List.of(disburse ? "disburse" : "sign", id, version),
        () -> {
          apps.expected(app, version);
          apps.require(app, disburse ? SIGNED_SIMULATED : ACCEPTED);
          if (disburse) {
            Long amount =
                db.queryForObject(
                    "select amount_minor from core.offer where id=?",
                    Long.class,
                    contract.get("offer_id"));
            db.update(
                "insert into core.simulated_ledger_entry(id,contract_id,amount_minor) values"
                    + " (?,?,?)",
                UUID.randomUUID(),
                id,
                amount);
            apps.transition(
                app, DISBURSED_SIMULATED, "DISBURSEMENT_SIMULATED", "USER", user.toString());
          } else {
            db.update("update core.contract set signed_at=now() where id=?", id);
            apps.transition(app, SIGNED_SIMULATED, "SIGNATURE_SIMULATED", "USER", user.toString());
          }
          access.audit(user, disburse ? "SIMULATE_DISBURSE" : "SIMULATE_SIGN", id);
          return app.id;
        });
  }
}
