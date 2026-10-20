package ru.alfa.credit.core;

import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.alfa.credit.platform.*;

@Service
@Transactional
public class Profiles {
  private final JdbcTemplate db;
  private final Access access;
  private final Commands commands;
  private final Json json;

  public Profiles(JdbcTemplate db, Access access, Commands commands, Json json) {
    this.db = db;
    this.access = access;
    this.commands = commands;
    this.json = json;
  }

  public Map<String, Object> user(UUID user) {
    return db.queryForMap(
        "select id,username,display_name,settings,version from auth.app_user where id=?", user);
  }

  public List<Map<String, Object>> companies(UUID user) {
    return db.queryForList(
        "select c.* from core.company c join auth.membership m on m.company_id=c.id where"
            + " m.user_id=? order by c.name",
        user);
  }

  public Map<String, Object> company(UUID user, UUID company) {
    access.company(user, company);
    var result = db.queryForMap("select * from core.company where id=?", company);
    result.put(
        "documents",
        db.queryForList(
            "select id,kind,filename,content_type,size,checksum,created_at from core.document where"
                + " company_id=? and state='READY' order by created_at desc",
            company));
    return result;
  }

  public UUID updateUser(UUID user, long version, String name, String locale, UUID command) {
    if (name == null
        || name.isBlank()
        || name.length() > 200
        || !Set.of("ru", "en").contains(locale)) throw Problem.invalid("INVALID_PROFILE");
    return commands.run(
        user,
        command,
        List.of("profile", version, name, locale),
        () -> {
          if (db.update(
                  "update auth.app_user set display_name=?,settings=?::jsonb,version=version+1"
                      + " where id=? and version=?",
                  name,
                  json.write(Map.of("locale", locale)),
                  user,
                  version)
              != 1) throw Problem.conflict("STALE_VERSION");
          access.audit(user, "PROFILE_UPDATE", user);
          return user;
        });
  }

  public UUID updateCompany(
      UUID user,
      UUID company,
      long version,
      String name,
      LocalDate registeredAt,
      String industry,
      UUID command) {
    access.company(user, company);
    if (name == null
        || name.isBlank()
        || name.length() > 200
        || registeredAt == null
        || registeredAt.isAfter(LocalDate.now())
        || industry == null
        || industry.length() > 100) throw Problem.invalid("INVALID_COMPANY");
    return commands.run(
        user,
        command,
        List.of("company", company, version, name, registeredAt, industry),
        () -> {
          if (db.update(
                  "update core.company set"
                      + " name=?,registered_at=?,profile=?::jsonb,version=version+1 where id=? and"
                      + " version=?",
                  name,
                  registeredAt,
                  json.write(Map.of("industry", industry)),
                  company,
                  version)
              != 1) throw Problem.conflict("STALE_VERSION");
          access.audit(user, "COMPANY_UPDATE", company);
          return company;
        });
  }
}
