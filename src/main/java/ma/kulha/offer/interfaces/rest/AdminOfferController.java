package ma.kulha.offer.interfaces.rest;

import jakarta.validation.Valid;
import java.util.List;
import ma.kulha.admin.application.AuditApi;
import ma.kulha.admin.application.AuditEntry;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.Objects;
import java.util.UUID;
import ma.kulha.offer.application.OfferApplicationService;
import ma.kulha.offer.interfaces.dto.OfferResponse;
import ma.kulha.offer.interfaces.dto.ReasonRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/offers")
public class AdminOfferController {
    private final OfferApplicationService offers;
    private final AuditApi audit;
    AdminOfferController(OfferApplicationService offers, AuditApi audit) { this.offers = offers; this.audit = audit; }
    @PostMapping("/{id}/suspend")
    OfferResponse suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return OfferResponse.from(offers.adminSuspend(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), id, body.reason()));
    }
    @GetMapping("/{id}/history")
    List<AuditEntry> history(@PathVariable UUID id) { return audit.history("OFFER", id.toString()); }

    @PostMapping("/{id}/reinstate")
    OfferResponse reinstate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return OfferResponse.from(offers.adminReinstate(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), id, body.reason()));
    }
}
