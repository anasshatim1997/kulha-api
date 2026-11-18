package ma.kulha.offer.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import ma.kulha.merchant.application.MerchantPublicApi;
import ma.kulha.merchant.application.MerchantPublicProfile;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.domain.model.PublicOfferRow;
import ma.kulha.offer.domain.repository.OfferRepository;
import ma.kulha.offer.infrastructure.search.ElasticsearchOfferSearch;
import ma.kulha.offer.infrastructure.search.OfferSearchDocument;
import ma.kulha.offer.interfaces.dto.PublicOfferDetailResponse;
import ma.kulha.offer.interfaces.dto.PublicOfferSummaryResponse;
import ma.kulha.shared.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OfferDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(OfferDiscoveryService.class);

    private final OfferRepository offers;
    private final MerchantPublicApi merchants;
    private final ElasticsearchOfferSearch search;
    private final Clock clock;
    private final Duration cutoff;

    OfferDiscoveryService(OfferRepository offers, MerchantPublicApi merchants, ElasticsearchOfferSearch search, Clock clock,
                          @Value("${app.offer.reservation-cutoff-minutes:30}") long cutoffMinutes) {
        this.offers = offers;
        this.merchants = merchants;
        this.search = search;
        this.clock = clock;
        if (cutoffMinutes < 0) throw new IllegalArgumentException("app.offer.reservation-cutoff-minutes cannot be negative");
        this.cutoff = Duration.ofMinutes(cutoffMinutes);
    }

    public PageResponse<PublicOfferSummaryResponse> search(String query, OfferCategory category, Integer maxPriceMinor,
            java.time.Instant pickupFrom, java.time.Instant pickupTo, String city, String district,
            boolean availableOnly, String sort, int page, int size) {
        return search(query, category, maxPriceMinor, pickupFrom, pickupTo, city, district, availableOnly, false,
                sort, page, size, null, null, null);
    }

    public PageResponse<PublicOfferSummaryResponse> search(String query, OfferCategory category, Integer maxPriceMinor,
            java.time.Instant pickupFrom, java.time.Instant pickupTo, String city, String district,
            boolean availableOnly, boolean lowStockOnly, String sort, int page, int size,
            Double latitude, Double longitude, Integer radiusMeters) {
        long requestStarted = System.nanoTime();
        var reservableAfter = clock.instant().plus(cutoff);
        var result = search.search(query, category, maxPriceMinor, pickupFrom, pickupTo, city, district,
                availableOnly, lowStockOnly, sort, page, size, latitude, longitude, radiusMeters, reservableAfter);
        long searchMillis = elapsedMillis(requestStarted);
        String searchMode = latitude != null && longitude != null && radiusMeters != null
                ? "nearby" : (query == null || query.isBlank() ? "browse" : "text");
        if (result.ids().isEmpty()) {
            warnIfSlow(requestStarted, searchMillis, 0, 0, page, size, searchMode);
            return new PageResponse<>(List.of(), page, size, result.total());
        }

        long offersStarted = System.nanoTime();
        List<PublicOfferRow> rows = offers.findPublicByIds(result.ids(), reservableAfter, availableOnly);
        Map<UUID, PublicOfferRow> rowsById = rows.stream().collect(Collectors.toMap(PublicOfferRow::id, Function.identity()));
        List<PublicOfferRow> orderedRows = result.ids().stream().map(rowsById::get).filter(java.util.Objects::nonNull).toList();
        long offersMillis = elapsedMillis(offersStarted);

        long merchantsStarted = System.nanoTime();
        Map<UUID, MerchantPublicProfile> profiles = profiles(orderedRows.stream().map(PublicOfferRow::merchantId).distinct().toList());
        List<PublicOfferSummaryResponse> items = orderedRows.stream()
                .filter(row -> profiles.containsKey(row.merchantId()))
                .map(row -> {
                    Double meters = result.distanceMeters().get(row.id());
                    Double distanceKm = meters == null ? null : (meters < 50 ? 0.1 : Math.round(meters / 100.0) / 10.0);
                    return PublicOfferSummaryResponse.from(row, profiles.get(row.merchantId()), distanceKm);
                })
                .toList();
        long merchantsMillis = elapsedMillis(merchantsStarted);
        warnIfSlow(requestStarted, searchMillis, offersMillis, merchantsMillis, page, size, searchMode);
        return new PageResponse<>(items, page, size, result.total());
    }

    public List<OfferSuggestion> suggest(String query, String city, String district, int limit) {
        return search.suggest(query, city, district, limit, clock.instant().plus(cutoff)).stream()
                .map(row -> new OfferSuggestion(row.id(), row.title(), row.category(), row.priceMinor(),
                        row.merchantName(), row.city(), row.district()))
                .toList();
    }

    public PublicOfferDetailResponse get(UUID id) {
        var row = offers.findPublicById(id, clock.instant().plus(cutoff)).orElseThrow(OfferNotFoundException::new);
        MerchantPublicProfile merchant = profiles(List.of(row.merchantId())).get(row.merchantId());
        if (merchant == null) throw new OfferNotFoundException();
        return PublicOfferDetailResponse.from(row, merchant);
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private void warnIfSlow(long requestStarted, long searchMillis, long offersMillis, long merchantsMillis,
                            int page, int size, String searchMode) {
        long totalMillis = elapsedMillis(requestStarted);
        if (totalMillis >= 1_000) {
            // Timings deliberately omit search text and customer coordinates. The mode
            // distinguishes a geo lookup from normal browse/text discovery without
            // leaking the user's search terms or exact location.
            log.warn("Slow public offer discovery: total={}ms, search={}ms, offerRows={}ms, merchantProfiles={}ms, mode={}, page={}, size={}",
                    totalMillis, searchMillis, offersMillis, merchantsMillis, searchMode, page, size);
        }
    }

    private Map<UUID, MerchantPublicProfile> profiles(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return merchants.findApprovedPublicProfiles(ids).stream()
                .collect(Collectors.toMap(MerchantPublicProfile::merchantId, Function.identity()));
    }
}
