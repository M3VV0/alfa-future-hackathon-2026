package dev.ammmmm.hackathon.smartcreditor.backend.controller;

import dev.ammmmm.hackathon.smartcreditor.backend.model.BusinessAccess;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationRequest;
import dev.ammmmm.hackathon.smartcreditor.backend.repository.BusinessAccessRepository;
import dev.ammmmm.hackathon.smartcreditor.backend.service.CreditApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@RestController
public class CreditApplicationController {
    private final BusinessAccessRepository businessAccessRepository;
    private final CreditApplicationService creditApplicationService;

    public CreditApplicationController(BusinessAccessRepository businessAccessRepository,
                                       CreditApplicationService creditApplicationService) {
        this.businessAccessRepository = businessAccessRepository;
        this.creditApplicationService = creditApplicationService;
    }

    @PostMapping(value = "/api/credit/application/submit",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApplicationAccepted> submit(@Valid @RequestBody CreditApplicationRequest request,
                                                      BindingResult errors, @AuthenticationPrincipal Jwt jwt) {
        if (errors.hasErrors()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid application request");
        }

        UUID userId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
        var access = businessAccessRepository.findByUserIdAndBusinessId(userId, request.businessId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));

        if (access.getRole() != BusinessAccess.Role.OWNER && access.getRole() != BusinessAccess.Role.ADMIN) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Insufficient permissions to submit an application");
        }

        UUID applicationId = creditApplicationService.submit(userId, request);
        return ResponseEntity.accepted().body(new ApplicationAccepted(applicationId));
    }

    public record ApplicationAccepted(UUID applicationId) {
    }
}
