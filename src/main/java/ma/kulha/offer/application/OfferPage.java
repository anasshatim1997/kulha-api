package ma.kulha.offer.application;

import java.util.List;
import ma.kulha.offer.domain.model.OfferSummary;

public record OfferPage(List<OfferSummary> items, long total) {
    public OfferPage {
        items = List.copyOf(items);
    }
}
