package ru.alfa.credit.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.alfa.credit.platform.Problem;

/** Web-only transport. Template implementation is intentionally deferred at the user's request. */
@Controller
@RequestMapping("/app")
public class WebController {
  private final Applications apps;
  private final Access access;
  private final Profiles profiles;
  private final Documents documents;
  private final Offers offers;
  private final JdbcTemplate db;

  public WebController(
      Applications apps,
      Access access,
      Profiles profiles,
      Documents documents,
      Offers offers,
      JdbcTemplate db) {
    this.apps = apps;
    this.access = access;
    this.profiles = profiles;
    this.documents = documents;
    this.offers = offers;
    this.db = db;
  }

  private UUID user(Principal p) {
    return access.user(p.getName());
  }

  private ResponseEntity<Void> redirect(HttpServletRequest request, String path) {
    if ("true".equalsIgnoreCase(request.getHeader("HX-Request")))
      return ResponseEntity.ok().header("HX-Redirect", path).build();
    return ResponseEntity.status(303).location(java.net.URI.create(path)).build();
  }

  @ModelAttribute
  void privateResponses(jakarta.servlet.http.HttpServletResponse res, Model model) {
    model.addAttribute("commandId", UUID.randomUUID());
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Vary", "HX-Request");
  }

  @GetMapping("/profile")
  String profile(Principal p, Model model) {
    model.addAttribute("profile", profiles.user(user(p)));
    return "profile";
  }

  @PostMapping("/profile")
  ResponseEntity<Void> profile(
      Principal p,
      @RequestParam long version,
      @RequestParam String name,
      @RequestParam(defaultValue = "ru") String locale,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    profiles.updateUser(user(p), version, name, locale, command);
    return redirect(req, "/app/profile");
  }

  @GetMapping("/companies")
  String companies(Principal p, Model model) {
    model.addAttribute("companies", profiles.companies(user(p)));
    return "companies";
  }

  @GetMapping("/companies/{id}")
  String company(Principal p, @PathVariable UUID id, Model model) {
    model.addAttribute("company", profiles.company(user(p), id));
    return "company";
  }

  @PostMapping("/companies/{id}")
  ResponseEntity<Void> company(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestParam String name,
      @RequestParam LocalDate registeredAt,
      @RequestParam String industry,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    profiles.updateCompany(user(p), id, version, name, registeredAt, industry, command);
    return redirect(req, "/app/companies/" + id);
  }

  @GetMapping({"/credit", "/credit/{id}"})
  String products(@PathVariable(required = false) String id, Model model) {
    var products =
        id == null
            ? db.queryForList("select * from core.product order by id,version desc")
            : db.queryForList("select * from core.product where id=? order by version desc", id);
    if (id != null && products.isEmpty()) throw Problem.missing();
    model.addAttribute("products", products);
    return "credit";
  }

  @GetMapping("/applications")
  String applications(Principal p, Model model) {
    model.addAttribute("applications", apps.list(user(p)));
    return "applications";
  }

  @PostMapping("/applications")
  ResponseEntity<Void> create(
      Principal p,
      @RequestParam UUID companyId,
      @RequestParam String productId,
      @ModelAttribute @Valid Draft draft,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    UUID id = apps.create(user(p), companyId, productId, draft, command);
    return redirect(req, "/app/applications/" + id);
  }

  @GetMapping("/applications/{id}")
  String details(Principal p, @PathVariable UUID id, Model model, HttpServletRequest req) {
    model.addAttribute("application", apps.detail(user(p), id));
    return "true".equalsIgnoreCase(req.getHeader("HX-Request"))
        ? "application :: detail"
        : "application";
  }

  @PostMapping("/applications/{id}")
  ResponseEntity<Void> save(
      Principal p,
      @PathVariable UUID id,
      @RequestParam long version,
      @ModelAttribute @Valid Draft draft,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req) {
    apps.save(user(p), id, version, draft, command);
    return redirect(req, "/app/applications/" + id);
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
    model.addAttribute("application", apps.detail(user(p), id));
    return "application :: status";
  }

  @GetMapping("/applications/{id}/history")
  String history(Principal p, @PathVariable UUID id, Model model) {
    model.addAttribute("application", apps.detail(user(p), id));
    return "application :: history";
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<Void> upload(
      Principal p,
      @RequestParam UUID companyId,
      @RequestParam String kind,
      @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
      @RequestParam("command_id") UUID command,
      HttpServletRequest req)
      throws java.io.IOException {
    UUID document =
        documents.upload(
            user(p),
            companyId,
            kind,
            file.getOriginalFilename(),
            file.getContentType(),
            file.getBytes(),
            command);
    return redirect(req, "/app/companies/" + companyId + "#document-" + document);
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
                .filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(file.bytes());
  }

  @GetMapping("/offers/{id}")
  String offer(Principal p, @PathVariable UUID id, Model model) {
    model.addAttribute("offer", offers.offer(user(p), id));
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
    model.addAttribute("contract", offers.contract(user(p), id));
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
