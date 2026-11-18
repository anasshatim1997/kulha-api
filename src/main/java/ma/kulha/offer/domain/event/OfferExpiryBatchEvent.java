package ma.kulha.offer.domain.event;

import java.util.List;
import java.util.UUID;

public record OfferExpiryBatchEvent(List<UUID> offerIds) {
    public OfferExpiryBatchEvent {
        offerIds = List.copyOf(offerIds);
    }
}
