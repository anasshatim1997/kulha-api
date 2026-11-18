package ma.kulha.offer.infrastructure.search;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record OfferSearchPage(List<UUID> ids, long total, Map<UUID, Double> distanceMeters) {
    public OfferSearchPage(List<UUID> ids, long total) {
        this(ids, total, Map.of());
    }

    public OfferSearchPage {
        ids = List.copyOf(ids);
        distanceMeters = distanceMeters == null ? Map.of() : Map.copyOf(distanceMeters);
    }
}
