package ru.alfa.credit.core;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import ru.alfa.credit.platform.*;

/** The single browser transport: server-rendered pages, forms and HTMX fragments. */
@Controller
@RequestMapping("/app")
public class WebController {
  private final Applications apps;
  private final Access access;
  private final Profiles profiles;
  private final Documents documents;
  private final Offers offers;
  private final JdbcTemplate db;
  private final WebModels views;
  private final Ui ui;
  private final Json json;

  public WebController(
      Applications apps,
      Access access,
      Profiles profiles,
      Documents documents,
      Offers offers,
      JdbcTemplate db,
      WebModels views,
      Ui ui,
      Json json) {
    this.apps = apps;
    this.access = access;
    this.profiles = profiles;
    this.documents = documents;
    this.offers = offers;
    this.db = db;
    this.views = views;
    this.ui = ui;
    this.json = json;
  }

  private UUID user(Principal p) {
    return access.user(p.getName());
  }

  private boolean hx(HttpServletRequest request) {
    return "true".equalsIgnoreCase(request.getHeader("HX-Request"));
  }

  private ResponseEntity<Void> redirect(HttpServletRequest request, String path) {
    if (hx(request)) return ResponseEntity.ok().header("HX-Redirect", path).build();
    return ResponseEntity.status(303).location(java.net.URI.create(path)).build();
  }

  private ModelAndView invalid(Model model, String page, String fragment, HttpServletRequest req) {
    return new ModelAndView(
        hx(req) ? fragment : page, model.asMap(), HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @ModelAttribute
  void common(
      jakarta.servlet.http.HttpServletResponse res,
      Model model,
      Principal p,
      HttpServletRequest req) {
    model.addAttribute("commandId", UUID.randomUUID());
    model.addAttribute("currentUser", profiles.user(user(p)));
    model.addAttribute("companies", profiles.companies(user(p)));
    String path = req.getRequestURI();
    model.addAttribute(
        "nav",
        path.contains("/credit") || path.contains("/applications/new")
            ? "credit"
            : path.contains("/companies")
                ? "companies"
                : path.contains("/profile") ? "profile" : "applications");
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Vary", "HX-Request");
  }

  @GetMapping("/profile")
  String profile(Principal p, Model model) {
    model.addAttribute("profile", profiles.user(user(p)));
    return "profile";
  }

  @PostMapping("/profile")
  Object profile(
      Principal p,
      @RequestParam long version,
      @RequestParam String name,
      @RequestParam(defaultValue = "ru") String locale,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req,
      Model model) {
    try {
      profiles.updateUser(user(p), version, name, locale, command);
    } catch (Problem e) {
      if (e.status != 422) throw e;
      var profile = profiles.user(user(p));
      profile.put("display_name", name);
      model.addAttribute("profile", profile);
      model.addAttribute("formError", ui.error(e.code));
      return invalid(model, "profile", "profile :: editor", req);
    }
    return redirect(req, "/app/profile?saved");
  }

  @GetMapping("/companies")
  String companies() {
    return "companies";
  }

  @GetMapping("/companies/{id}")
  String company(Principal p, @PathVariable UUID id, Model model) {
    views.company(model, user(p), id);
    model.addAttribute("documentIds", List.of());
    return "company";
  }

  @PostMapping("/companies/{id}")
  Object company(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestParam String name,
      @RequestParam LocalDate registeredAt,
      @RequestParam String industry,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req,
      Model model) {
    try {
      profiles.updateCompany(user(p), id, version, name, registeredAt, industry, command);
    } catch (Problem e) {
      if (e.status != 422) throw e;
      var company = profiles.company(user(p), id);
      company.put("name", name);
      company.put("registered_at", registeredAt);
      company.put("profile", json.write(Map.of("industry", industry)));
      model.addAttribute("company", company);
      model.addAttribute("formError", ui.error(e.code));
      model.addAttribute("documentIds", List.of());
      return invalid(model, "company", "company :: editor", req);
    }
    return redirect(req, "/app/companies/" + id + "?saved");
  }

  @GetMapping({"/credit", "/credit/{id}"})
  String products(@PathVariable(required = false) String id, Model model) {
    var products =
        id == null
            ? db.queryForList("select distinct on(id) * from core.product order by id,version desc")
            : List.of(views.product(id));
    model.addAttribute("products", products);
    return "credit";
  }

  @GetMapping("/applications")
  String applications(
      Principal p,
      Model model,
      @RequestParam(defaultValue = "all") String filter,
      @RequestParam(defaultValue = "") String q) {
    var all = apps.list(user(p));
    model.addAttribute("totalCount", all.size());
    model.addAttribute(
        "activeCount",
        all.stream()
            .filter(a -> ui.active(a.get("status")) || "NEEDS_INFO".equals(a.get("status")))
            .count());
    model.addAttribute(
        "draftCount", all.stream().filter(a -> "DRAFT".equals(a.get("status"))).count());
    String search = q.length() > 100 ? q.substring(0, 100) : q;
    String normalized = search.toLowerCase(Locale.ROOT).trim();
    model.addAttribute(
        "applications",
        all.stream()
            .filter(
                a ->
                    switch (filter) {
                      case "active" ->
                          ui.active(a.get("status")) || "NEEDS_INFO".equals(a.get("status"));
                      case "drafts" -> "DRAFT".equals(a.get("status"));
                      case "completed" ->
                          !ui.active(a.get("status")) && !ui.editable(a.get("status"));
                      default -> true;
                    })
            .filter(
                a ->
                    normalized.isEmpty()
                        || (a.get("id").toString()
                                + " "
                                + ui.product(a.get("product_id"))
                                + " "
                                + ui.field(a.get("draft"), "purpose"))
                            .toLowerCase(Locale.ROOT)
                            .contains(normalized))
            .toList());
    model.addAttribute("filter", filter);
    model.addAttribute("query", search);
    return "applications";
  }

  @GetMapping("/applications/new")
  String newApplication(
      Principal p,
      @RequestParam(defaultValue = "working-capital") String productId,
      @RequestParam(required = false) UUID companyId,
      Model model) {
    views.newApplication(model, user(p), companyId, productId);
    return "new-application";
  }

  @PostMapping("/applications")
  Object create(
      Principal p,
      @RequestParam UUID companyId,
      @RequestParam String productId,
      @ModelAttribute("form") LoanForm form,
      BindingResult errors,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req,
      Model model) {
    Draft draft = form.draft(errors);
    if (!errors.hasErrors())
      try {
        return redirect(
            req, "/app/applications/" + apps.create(user(p), companyId, productId, draft, command));
      } catch (Problem e) {
        if (e.status != 422) throw e;
        errors.reject("invalid", ui.error(e.code));
      }
    views.newApplication(model, user(p), companyId, productId);
    return invalid(model, "new-application", "new-application :: editor", req);
  }

  @GetMapping("/applications/{id}")
  String details(Principal p, @PathVariable UUID id, Model model, HttpServletRequest req) {
    views.application(model, user(p), id);
    return hx(req) ? "application :: detail" : "application";
  }

  @PostMapping("/applications/{id}")
  Object save(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @ModelAttribute("form") LoanForm form,
      BindingResult errors,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req,
      Model model) {
    Draft draft = form.draft(errors);
    if (!errors.hasErrors())
      try {
        apps.save(user(p), id, version, draft, command);
        return redirect(req, "/app/applications/" + id + "?saved");
      } catch (Problem e) {
        if (e.status != 422) throw e;
        errors.reject("invalid", ui.error(e.code));
      }
    views.application(model, user(p), id);
    return invalid(model, "application", "application :: editor", req);
  }

  @PostMapping("/applications/{id}/submit")
  ResponseEntity<Void> submit(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    apps.submit(user(p), id, version, command, false);
    return redirect(req, "/app/applications/" + id);
  }

  @PostMapping("/applications/{id}/resubmit")
  ResponseEntity<Void> resubmit(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    apps.submit(user(p), id, version, command, true);
    return redirect(req, "/app/applications/" + id);
  }

  @PostMapping("/applications/{id}/cancel")
  ResponseEntity<Void> cancel(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    apps.cancel(user(p), id, version, command);
    return redirect(req, "/app/applications/" + id);
  }

  @GetMapping("/applications/{id}/status")
  String status(Principal p, @PathVariable UUID id, Model model) {
    views.application(model, user(p), id);
    model.addAttribute("statusPoll", true);
    return "application :: status";
  }

  @GetMapping("/applications/{id}/history")
  String history(Principal p, @PathVariable UUID id, Model model) {
    model.addAttribute("loan", apps.detail(user(p), id));
    return "application :: history";
  }

  @GetMapping("/applications/{id}/report-template")
  ResponseEntity<byte[]> report(Principal p, @PathVariable UUID id) {
    var app = apps.detail(user(p), id);
    Draft draft = json.parse(app.get("draft").toString(), Draft.class);
    if (draft.revenueMinor() == null || draft.expensesMinor() == null || draft.period() == null)
      throw Problem.invalid("REPORT_DATA_REQUIRED");
    var company = profiles.company(user(p), (UUID) app.get("companyId"));
    byte[] report =
        json.write(
                Map.of(
                    "inn",
                    company.get("inn"),
                    "period",
                    draft.period(),
                    "revenueMinor",
                    draft.revenueMinor(),
                    "expensesMinor",
                    draft.expensesMinor()))
            .getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header("Content-Disposition", "attachment; filename=financial-report.json")
        .body(report);
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Object upload(
      Principal p,
      @RequestParam UUID companyId,
      @RequestParam String kind,
      @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
      @RequestParam("command_id") UUID command,
      @RequestParam(required = false) UUID applicationId,
      @RequestParam(required = false) List<UUID> documentIds,
      HttpServletRequest req,
      Model model)
      throws java.io.IOException {
    UUID actor = user(p);
    if (applicationId != null) {
      var app = apps.detail(actor, applicationId);
      if (!companyId.equals(app.get("companyId"))) throw Problem.missing();
    }
    UUID document;
    try {
      document =
          documents.upload(
              actor,
              companyId,
              kind,
              file.getOriginalFilename(),
              file.getContentType(),
              file.getBytes(),
              command);
    } catch (Problem e) {
      if (e.status != 422 || !hx(req)) throw e;
      views.company(model, actor, companyId);
      model.addAttribute("documentIds", documentIds == null ? List.of() : documentIds);
      model.addAttribute("uploadApplicationId", applicationId);
      model.addAttribute("uploadError", ui.error(e.code));
      return invalid(model, "company", "fragments/documents :: panel", req);
    }
    if (hx(req) && "documents-panel".equals(req.getHeader("HX-Target"))) {
      if (applicationId != null) views.application(model, actor, applicationId);
      else views.company(model, actor, companyId);
      Set<UUID> selected = new HashSet<>(documentIds == null ? List.of() : documentIds);
      selected.add(document);
      model.addAttribute("documentIds", selected);
      model.addAttribute("uploaded", true);
      model.addAttribute("uploadApplicationId", applicationId);
      return new ModelAndView("fragments/documents :: panel", model.asMap());
    }
    return redirect(
        req,
        applicationId == null
            ? "/app/companies/" + companyId + "#document-" + document
            : "/app/applications/" + applicationId + "#documents-panel");
  }

  @GetMapping("/documents/{id}/content")
  ResponseEntity<byte[]> download(Principal p, @PathVariable UUID id) {
    var file = documents.download(user(p), id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.contentType()))
        .header("X-Content-Type-Options", "nosniff")
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(file.bytes());
  }

  @GetMapping("/offers/{id}")
  String offer(Principal p, @PathVariable UUID id, Model model) {
    var offer = offers.offer(user(p), id);
    model.addAttribute("offer", offer);
    views.application(model, user(p), (UUID) offer.get("application_id"));
    return "offer";
  }

  @PostMapping("/offers/{id}/accept")
  ResponseEntity<Void> accept(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    return redirect(
        req, "/app/applications/" + offers.respond(user(p), id, version, command, true));
  }

  @PostMapping("/offers/{id}/decline")
  ResponseEntity<Void> decline(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    return redirect(
        req, "/app/applications/" + offers.respond(user(p), id, version, command, false));
  }

  @GetMapping("/contracts/{id}")
  String contract(Principal p, @PathVariable UUID id, Model model) {
    var contract = offers.contract(user(p), id);
    model.addAttribute("contract", contract);
    views.application(model, user(p), (UUID) contract.get("application_id"));
    return "contract";
  }

  @PostMapping("/contracts/{id}/sign-simulated")
  ResponseEntity<Void> sign(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    return redirect(
        req, "/app/applications/" + offers.simulate(user(p), id, version, command, false));
  }

  @PostMapping("/contracts/{id}/disburse-simulated")
  ResponseEntity<Void> disburse(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    return redirect(
        req, "/app/applications/" + offers.simulate(user(p), id, version, command, true));
  }
}
