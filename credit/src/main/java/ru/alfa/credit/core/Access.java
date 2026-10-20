package ru.alfa.credit.core;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.alfa.credit.platform.*;

@Component
public class Access {
  private final JdbcTemplate db;

  public Access(JdbcTemplate db) {
    this.db = db;
  }

  public UUID user(String username) {
    return db
        .query(
            "select id from auth.app_user where username=?",
            (rs, n) -> rs.getObject(1, UUID.class),
            username)
        .stream()
        .findFirst()
        .orElseThrow(Problem::missing);
  }

  public void company(UUID user, UUID company) {
    if (db.queryForObject(
            "select count(*) from auth.membership where user_id=? and company_id=?",
            Integer.class,
            user,
            company)
        == 0) throw Problem.missing();
  }

  public void audit(UUID actor, String action, UUID resource) {
    db.update(
        "insert into core.audit_event(actor,action,resource_id,trace_id) values (?,?,?,?)",
        actor.toString(),
        action,
        resource,
        TraceFilter.current());
  }
}
