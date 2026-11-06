package ma.kulha.offer.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OfferApi {
    boolean tryHoldUnits(UUID offerId, int units);
    void releaseUnits(UUID offerId, int units);
    boolean isReservable(UUID offerId, Instant now);
    Optional<OfferSnapshot> snapshot(UUID offerId);
}
