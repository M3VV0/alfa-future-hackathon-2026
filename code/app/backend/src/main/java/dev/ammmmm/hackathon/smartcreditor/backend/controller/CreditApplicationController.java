package dev.ammmmm.hackathon.smartcreditor.backend.controller;

import dev.ammmmm.hackathon.smartcreditor.backend.credit.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/credit")
public class CreditApplicationController {
    private final CreditCabinetService cabinet;
    public CreditApplicationController(CreditCabinetService cabinet) { this.cabinet = cabinet; }

    @ModelAttribute
    public void noCache(HttpServletResponse response) { response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store"); }

    @GetMapping(value = "/openapi", produces = "application/yaml")
    public org.springframework.core.io.Resource openapi() {
        return new org.springframework.core.io.ClassPathResource("openapi/credit.yaml");
    }

    @GetMapping("/products")
    public List<CreditProduct> products() { return CreditProduct.CATALOG; }

    @GetMapping("/companies")
    public List<Map<String, Object>> companies(@AuthenticationPrincipal Jwt jwt) { return cabinet.companies(user(jwt)); }

    @PutMapping(value = "/companies/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> updateCompany(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                             @Valid @RequestBody CreditCabinetService.CompanyInput input) {
        cabinet.updateCompany(user(jwt), id, input);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/applications")
    public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) { return cabinet.list(user(jwt)); }

    @PostMapping(value = "/applications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, UUID>> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody CabinetInput input) {
        UUID id = cabinet.create(user(jwt), key, input);
        return ResponseEntity.created(java.net.URI.create("/api/credit/applications/" + id)).body(Map.of("applicationId", id));
    }

    @GetMapping("/applications/{id}")
    public Map<String, Object> view(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return cabinet.view(user(jwt), id); }

    @PutMapping(value = "/applications/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> edit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody CabinetInput input) {
        cabinet.edit(user(jwt), id, input);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/applications/{id}/submit")
    public ResponseEntity<Void> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        cabinet.submit(user(jwt), id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping(value = "/applications/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, UUID>> upload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > 1048576) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "REPORT_MAX_1_MB");
        // Parse and validate actual JSON content in the service; never trust filenames or browser MIME.
        UUID document = cabinet.upload(user(jwt), id, file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("documentId", document));
    }

    @GetMapping("/applications/{id}/documents/{document}")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID document) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=financial-report.json")
                .header("X-Content-Type-Options", "nosniff").body(cabinet.download(user(jwt), id, document));
    }

    @PostMapping("/applications/{id}/actions/{action}")
    public ResponseEntity<Void> action(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable String action) {
        cabinet.action(user(jwt), id, action);
        return ResponseEntity.noContent().build();
    }

    private static UUID user(Jwt jwt) { return UUID.fromString(Objects.requireNonNull(jwt.getSubject())); }
}
