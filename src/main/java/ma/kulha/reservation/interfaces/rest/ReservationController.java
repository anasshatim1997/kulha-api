package ma.kulha.reservation.interfaces.rest;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import jakarta.validation.Valid;
import ma.kulha.reservation.application.ReservationService;
import ma.kulha.reservation.interfaces.dto.CancelReservationRequest;
import ma.kulha.reservation.interfaces.dto.CollectReservationRequest;
import ma.kulha.reservation.interfaces.dto.CreateReservationRequest;
import ma.kulha.reservation.interfaces.dto.ReservationAvailabilityResponse;
import ma.kulha.reservation.interfaces.dto.ReservationResponse;
import ma.kulha.reservation.interfaces.dto.PickupPreviewResponse;
import ma.kulha.shared.web.PageResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {
    private final ReservationService reservations;
    ReservationController(ReservationService reservations) { this.reservations = reservations; }
    @GetMapping("/availability")
    ReservationAvailabilityResponse availability(@RequestParam UUID offerId,
            @RequestParam(required = false) UUID merchantId, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "1") int quantity) {
        return reservations.availability(offerId, merchantId, from, to, quantity);
    }
    @PostMapping
    ResponseEntity<ReservationResponse> create(@Valid @RequestBody CreateReservationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reservations.create(userId(authentication), request, locale(), idempotencyKey));
    }
    @GetMapping
    PageResponse<ReservationResponse> list(@RequestParam(defaultValue = "upcoming") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        return reservations.list(userId(authentication), status, page, size, locale());
    }
    @GetMapping("/{reference}")
    ReservationResponse get(@PathVariable String reference, Authentication authentication) {
        return reservations.get(reference, userId(authentication), hasAnyRole(authentication, "ADMIN", "SUPER_ADMIN"),
                hasAnyRole(authentication, "MERCHANT", "MERCHANT_OWNER", "MERCHANT_STAFF"), locale());
    }
    @PatchMapping("/{reference}/cancel")
    ReservationResponse cancel(@PathVariable String reference, @Valid @RequestBody(required = false) CancelReservationRequest request,
            Authentication authentication) {
        return reservations.cancel(reference, userId(authentication), request, locale());
    }
    @PostMapping("/pickup/check")
    PickupPreviewResponse previewPickup(@Valid @RequestBody CollectReservationRequest request, Authentication authentication) {
        return reservations.previewPickup(request, userId(authentication));
    }
    @PostMapping("/collect")
    ReservationResponse collect(@Valid @RequestBody CollectReservationRequest request, Authentication authentication) {
        return reservations.collect(request, userId(authentication), locale());
    }
    private static UUID userId(Authentication authentication) { return UUID.fromString(authentication.getName()); }
    private static boolean hasAnyRole(Authentication authentication, String... roles) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> java.util.Arrays.stream(roles).anyMatch(role -> authority.equals("ROLE_" + role)));
    }
    private static Locale locale() {
        Locale locale = LocaleContextHolder.getLocale();
        String language = locale == null ? "fr" : locale.getLanguage();
        return switch (language) {
            case "en" -> Locale.ENGLISH;
            case "ar" -> Locale.forLanguageTag("ar-MA");
            default -> Locale.FRENCH;
        };
    }
}
