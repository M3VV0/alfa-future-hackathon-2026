package ru.alfa.credit.core;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.alfa.credit.platform.*;

@Component
public class Commands {
  private final JdbcTemplate db;
  private final Json json;

  public Commands(JdbcTemplate db, Json json) {
    this.db = db;
    this.json = json;
  }

  public UUID run(UUID user, UUID commandId, Object payload, Supplier<UUID> action) {
    if (commandId == null) throw Problem.invalid("COMMAND_ID_REQUIRED");
    // Serializes concurrent copies of the same command, including commands creating a new
    // aggregate.
    db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", user + ":" + commandId);
    String hash = json.hash(payload);
    var previous =
        db.queryForList(
            "select * from core.command where user_id=? and command_id=?", user, commandId);
    if (!previous.isEmpty()) {
      if (!hash.equals(previous.getFirst().get("payload_hash")))
        throw Problem.conflict("COMMAND_PAYLOAD_MISMATCH");
      return (UUID) previous.getFirst().get("result_id");
    }
    UUID result = action.get();
    db.update(
        "insert into core.command(user_id,command_id,payload_hash,result_id) values (?,?,?,?)",
        user,
        commandId,
        hash,
        result);
    return result;
  }
}
