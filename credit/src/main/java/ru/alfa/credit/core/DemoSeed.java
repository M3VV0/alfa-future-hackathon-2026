package ru.alfa.credit.core;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "seed.enabled", havingValue = "true")
public class DemoSeed implements ApplicationRunner {
  public static final UUID ALICE = UUID.fromString("10000000-0000-0000-0000-000000000001"),
      BOB = UUID.fromString("10000000-0000-0000-0000-000000000002");
  public static final UUID COMPANY_A = UUID.fromString("20000000-0000-0000-0000-000000000001"),
      COMPANY_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final String password;

  public DemoSeed(
      JdbcTemplate db, PasswordEncoder encoder, @Value("${seed.password}") String password) {
    this.db = db;
    this.encoder = encoder;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (password.length() < 12)
      throw new IllegalArgumentException("DEMO_PASSWORD must contain at least 12 characters");
    db.update(
        "insert into auth.app_user(id,username,password_hash,display_name) values (?,?,?,?) on"
            + " conflict do nothing",
        ALICE,
        "alice",
        encoder.encode(password),
        "Тестовый представитель А");
    db.update(
        "insert into auth.app_user(id,username,password_hash,display_name) values (?,?,?,?) on"
            + " conflict do nothing",
        BOB,
        "bob",
        encoder.encode(password),
        "Тестовый представитель Б");
    db.update(
        "insert into core.company(id,inn,name,registered_at,profile) values (?,?,'Синтетическая"
            + " компания А','2023-01-01','{\"industry\":\"Торговля\"}') on conflict do nothing",
        COMPANY_A,
        "9900000017");
    db.update(
        "insert into core.company(id,inn,name,registered_at,profile) values (?,?,'Синтетическая"
            + " компания Б','2023-01-01','{\"industry\":\"Производство\"}') on conflict do nothing",
        COMPANY_B,
        "9900000024");
    db.update("insert into auth.membership values (?,?) on conflict do nothing", ALICE, COMPANY_A);
    db.update("insert into auth.membership values (?,?) on conflict do nothing", BOB, COMPANY_B);
    db.update(
        "insert into core.product values"
            + " ('working-capital',1,'Оборотный',10000000,300000000,6,24,0.18,6,0.45) on conflict"
            + " do nothing");
    db.update(
        "insert into core.product values"
            + " ('equipment',1,'Оборудование',50000000,1000000000,12,60,0.16,12,0.50) on conflict"
            + " do nothing");
  }
}
