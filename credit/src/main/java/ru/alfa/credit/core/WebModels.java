package ru.alfa.credit.core;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import ru.alfa.credit.platform.*;

@Component
public class WebModels {
  private final Applications apps;
  private final Profiles profiles;
  private final JdbcTemplate db;
  private final Json json;

  public WebModels(Applications apps, Profiles profiles, JdbcTemplate db, Json json) {
    this.apps = apps;
    this.profiles = profiles;
    this.db = db;
    this.json = json;
  }

  public void company(Model model, UUID user, UUID company) {
    model.addAttribute("company", profiles.company(user, company));
  }

  public Map<String, Object> application(Model model, UUID user, UUID id) {
    var app = apps.detail(user, id);
    app.put("status", app.get("status").toString());
    app.put(
        "contracts", db.queryForList("select id from core.contract where application_id=?", id));
    model.addAttribute("loan", app);
    company(model, user, (UUID) app.get("companyId"));
    model.addAttribute(
        "product",
        product(app.get("productId").toString(), ((Number) app.get("productVersion")).intValue()));
    if (!model.containsAttribute("form"))
      model.addAttribute(
          "form", LoanForm.from(json.parse(app.get("draft").toString(), Draft.class)));
    model.addAttribute("uploadApplicationId", id);
    model.addAttribute(
        "documentIds", json.parse(app.get("draft").toString(), Draft.class).documentIds());
    return app;
  }

  public Map<String, Object> product(String id) {
    var rows =
        db.queryForList("select * from core.product where id=? order by version desc limit 1", id);
    if (rows.isEmpty()) throw Problem.missing();
    return rows.getFirst();
  }

  private Map<String, Object> product(String id, int version) {
    var rows = db.queryForList("select * from core.product where id=? and version=?", id, version);
    if (rows.isEmpty()) throw Problem.missing();
    return rows.getFirst();
  }

  public UUID newApplication(Model model, UUID user, UUID company, String product) {
    var allowed = profiles.companies(user);
    if (allowed.isEmpty()) throw Problem.missing();
    UUID selected = company == null ? (UUID) allowed.getFirst().get("id") : company;
    company(model, user, selected);
    model.addAttribute("product", product(product));
    model.addAttribute("productId", product);
    model.addAttribute("selectedCompanyId", selected);
    if (!model.containsAttribute("form")) {
      LoanForm form = new LoanForm();
      form.setTermMonths("12");
      form.setAmountRubles(product.equals("equipment") ? "2000000" : "1000000");
      var previous =
          db.queryForList(
              "select draft::text from core.application where company_id=? order by created_at desc"
                  + " limit 1",
              selected);
      if (!previous.isEmpty()) {
        var prior =
            LoanForm.from(json.parse(previous.getFirst().get("draft").toString(), Draft.class));
        form.setRevenueRubles(prior.getRevenueRubles());
        form.setExpensesRubles(prior.getExpensesRubles());
        form.setDebtRubles(prior.getDebtRubles());
        form.setPeriod(prior.getPeriod());
      }
      model.addAttribute("form", form);
    }
    return selected;
  }
}
