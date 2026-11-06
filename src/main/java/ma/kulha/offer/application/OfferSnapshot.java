package ma.kulha.offer.application;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.offer.domain.model.OfferStatus;

public record OfferSnapshot(UUID offerId, UUID merchantId, int priceMinor, Instant pickupStart,
                            Instant pickupEnd, OfferStatus status, int availableUnits) { }
