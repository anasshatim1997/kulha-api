package ma.kulha.offer.infrastructure.search;

import java.util.List;
import java.util.UUID;
import ma.kulha.offer.domain.model.OfferCategory;

public interface ElasticsearchOfferSearch {
    void index(OfferSearchDocument document);
    void delete(UUID offerId);
    void applyBatch(List<OfferSearchDocument> upserts, List<UUID> deletes);
    void clear();
    List<OfferSearchDocument> suggest(String query, String city, String district, int limit, java.time.Instant reservableAfter);

    OfferSearchPage search(String query, OfferCategory category, Integer maxPriceMinor,
                           java.time.Instant pickupFrom, java.time.Instant pickupTo,
                           String city, String district, boolean availableOnly, boolean lowStockOnly, String sort,
                           int page, int size, Double latitude, Double longitude, Integer radiusMeters,
                           java.time.Instant reservableAfter);

    default OfferSearchPage search(String query, OfferCategory category, Integer maxPriceMinor,
            java.time.Instant pickupFrom, java.time.Instant pickupTo, String city, String district,
            boolean availableOnly, String sort, int page, int size, java.time.Instant reservableAfter) {
        return search(query, category, maxPriceMinor, pickupFrom, pickupTo, city, district,
                availableOnly, false, sort, page, size, null, null, null, reservableAfter);
    }
}
