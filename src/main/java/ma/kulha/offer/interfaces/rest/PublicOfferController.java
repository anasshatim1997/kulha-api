package ma.kulha.offer.interfaces.rest;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.offer.application.OfferDiscoveryService;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.interfaces.dto.PublicOfferDetailResponse;
import ma.kulha.offer.interfaces.dto.PublicOfferSummaryResponse;
import ma.kulha.offer.interfaces.dto.PublicOfferSuggestionResponse;
import ma.kulha.offer.interfaces.dto.PublicOfferNearbyRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import ma.kulha.shared.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/offers")
public class PublicOfferController {
    private final OfferDiscoveryService discovery;

    PublicOfferController(OfferDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping
    PageResponse<PublicOfferSummaryResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) OfferCategory category,
            @RequestParam(required = false) Integer maxPriceMinor,
            @RequestParam(required = false) Instant pickupFrom,
            @RequestParam(required = false) Instant pickupTo,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String district,
            @RequestParam(defaultValue = "true") boolean availableOnly,
            @RequestParam(defaultValue = "false") boolean lowStockOnly,
            @RequestParam(defaultValue = "pickup_end") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String query = q == null || q.isBlank() ? null : q.trim();
        if (query != null && query.length() > 80) badRequest("q must contain at most 80 characters");
        if (maxPriceMinor != null && (maxPriceMinor < 100 || maxPriceMinor > 200000)) badRequest("maxPriceMinor must be between 100 and 200000");
        if (city != null && (city.isBlank() || city.length() > 80)) badRequest("city must contain 1..80 characters");
        if (district != null && (district.isBlank() || district.length() > 80)) badRequest("district must contain 1..80 characters");
        if (pickupFrom != null && pickupTo != null && pickupFrom.isAfter(pickupTo)) badRequest("pickupFrom must not be after pickupTo");
        if (!java.util.Set.of("pickup_end", "price", "newest", "savings", "low_stock").contains(sort))
            badRequest("sort must be pickup_end, price, newest, savings or low_stock");
        if (sort.equals("low_stock")) lowStockOnly = true;
        if (page < 0 || size < 1 || page > Integer.MAX_VALUE / Math.min(size, 50)) badRequest("page must be non-negative and size must be positive");
        int safeSize = Math.min(size, 50);
        return discovery.search(query, category, maxPriceMinor, pickupFrom, pickupTo,
                city == null ? null : city.trim(), district == null ? null : district.trim(),
                availableOnly, lowStockOnly, sort, page, safeSize, null, null, null);
    }

    @PostMapping("/nearby")
    PageResponse<PublicOfferSummaryResponse> nearby(@Valid @RequestBody PublicOfferNearbyRequest request) {
        if (!Double.isFinite(request.latitude()) || !Double.isFinite(request.longitude())) {
            badRequest("latitude and longitude must be finite numbers");
        }
        if (request.pickupFrom() != null && request.pickupTo() != null && request.pickupFrom().isAfter(request.pickupTo())) {
            badRequest("pickupFrom must not be after pickupTo");
        }
        String query = request.q() == null || request.q().isBlank() ? null : request.q().trim();
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        if (query != null && query.length() > 80) badRequest("q must contain at most 80 characters");
        if (request.maxPriceMinor() != null && (request.maxPriceMinor() < 100 || request.maxPriceMinor() > 200000))
            badRequest("maxPriceMinor must be between 100 and 200000");
        if (request.city() != null && (request.city().isBlank() || request.city().length() > 80))
            badRequest("city must contain 1..80 characters");
        if (request.district() != null && (request.district().isBlank() || request.district().length() > 80))
            badRequest("district must contain 1..80 characters");
        if (page < 0 || size < 1 || page > Integer.MAX_VALUE / Math.min(size, 50))
            badRequest("page must be non-negative and size must be positive");
        int safeSize = Math.min(size, 50);
        int radius = request.radiusMeters() == null ? 5000 : request.radiusMeters();
        return discovery.search(query, request.category(), request.maxPriceMinor(), request.pickupFrom(), request.pickupTo(),
                request.city() == null ? null : request.city().trim(),
                request.district() == null ? null : request.district().trim(),
                request.availableOnly() == null || request.availableOnly(), false, "distance", page, safeSize,
                request.latitude(), request.longitude(), radius);
    }

    @GetMapping("/suggestions")
    java.util.List<PublicOfferSuggestionResponse> suggestions(
            @RequestParam String q,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String district,
            @RequestParam(defaultValue = "6") int limit) {
        String query = q == null ? "" : q.trim();
        if (query.length() < 2 || query.length() > 80) {
            badRequest("q must contain between 2 and 80 characters");
        }
        if (city != null && (city.isBlank() || city.length() > 80)) badRequest("city must contain 1..80 characters");
        if (district != null && (district.isBlank() || district.length() > 80)) badRequest("district must contain 1..80 characters");
        if (limit < 1) badRequest("limit must be positive");
        return discovery.suggest(query, city == null ? null : city.trim(),
                        district == null ? null : district.trim(), Math.min(limit, 8))
                .stream().map(PublicOfferSuggestionResponse::from).toList();
    }

    @GetMapping("/{id}")
    PublicOfferDetailResponse get(@PathVariable UUID id) {
        return discovery.get(id);
    }

    private static void badRequest(String detail) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
