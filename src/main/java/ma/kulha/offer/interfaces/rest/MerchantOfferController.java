package ma.kulha.offer.interfaces.rest;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.web.bind.annotation.RequestHeader;
import java.util.UUID;
import ma.kulha.offer.application.OfferApplicationService;
import ma.kulha.offer.domain.model.InvalidOfferDataException;
import ma.kulha.offer.domain.model.OfferStatus;
import ma.kulha.offer.interfaces.dto.OfferRequest;
import ma.kulha.offer.interfaces.dto.OfferResponse;
import ma.kulha.offer.interfaces.dto.OfferSummaryResponse;
import ma.kulha.shared.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants/me/offers")
public class MerchantOfferController {
    private final OfferApplicationService offers;
    MerchantOfferController(OfferApplicationService offers) { this.offers = offers; }

    @PostMapping
    ResponseEntity<OfferResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OfferRequest body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        validateIdempotencyKey(key);
        var offer = offers.create(userId(jwt), body.title(), body.description(), body.category(), body.priceMinor(),
                body.originalPriceMinor(), body.quantityTotal(), body.pickupStart(), body.pickupEnd(),
                body.allergensStatus(), body.allergens(), body.foodNotes(), key, key == null ? null : hash("CREATE:" + body));
        return ResponseEntity.status(HttpStatus.CREATED).body(OfferResponse.from(offer));
    }

    @GetMapping
    PageResponse<OfferSummaryResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) OfferStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.max(1, Math.min(50, size));
        int safePage = Math.min(Math.max(0, page), Integer.MAX_VALUE / safeSize);
        var pageResult = offers.page(userId(jwt), status, safePage, safeSize);
        List<OfferSummaryResponse> items = pageResult.items().stream().map(OfferSummaryResponse::from).toList();
        return new PageResponse<>(items, safePage, safeSize, pageResult.total());
    }

    @GetMapping("/{id}")
    OfferResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return OfferResponse.from(offers.get(userId(jwt), id));
    }

    @PutMapping("/{id}")
    OfferResponse edit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody OfferRequest body) {
        if (body.version() == null) throw new InvalidOfferDataException("version is required");
        return OfferResponse.from(offers.edit(userId(jwt), id, body.version(), body.title(), body.description(),
                body.category(), body.priceMinor(), body.originalPriceMinor(), body.quantityTotal(), body.pickupStart(),
                body.pickupEnd(), body.allergensStatus(), body.allergens(), body.foodNotes()));
    }

    @PostMapping("/{id}/publish")
    OfferResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return OfferResponse.from(offers.publish(userId(jwt), id)); }
    @PostMapping("/{id}/unpublish")
    OfferResponse unpublish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return OfferResponse.from(offers.unpublish(userId(jwt), id)); }
    @PostMapping("/{id}/duplicate")
    ResponseEntity<OfferResponse> duplicate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        validateIdempotencyKey(key);
        return ResponseEntity.status(HttpStatus.CREATED).body(OfferResponse.from(
                offers.duplicate(userId(jwt), id, key, key == null ? null : hash("DUPLICATE:" + id))));
    }
    @DeleteMapping("/{id}")
    ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { offers.cancel(userId(jwt), id); return ResponseEntity.noContent().build(); }

    private static UUID userId(Jwt jwt) { return UUID.fromString(Objects.requireNonNull(jwt.getSubject())); }

    private static void validateIdempotencyKey(String key) {
        if (key != null && (key.isBlank() || key.length() > 128)) {
            throw new InvalidOfferDataException("Idempotency-Key must contain 1..128 characters");
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
