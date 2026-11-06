package ma.kulha.offer.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.merchant.application.MerchantApi;
import ma.kulha.offer.domain.repository.OfferRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
class OfferApiService implements OfferApi {
    private final OfferRepository offers;
    private final MerchantApi merchants;
    private final long reservationCutoffMinutes;

    OfferApiService(OfferRepository offers, MerchantApi merchants,
                    @Value("${app.offer.reservation-cutoff-minutes:30}") long reservationCutoffMinutes) {
        this.offers = offers;
        this.merchants = merchants;
        if (reservationCutoffMinutes < 0) throw new IllegalArgumentException("app.offer.reservation-cutoff-minutes cannot be negative");
        this.reservationCutoffMinutes = reservationCutoffMinutes;
    }

    @Override public boolean tryHoldUnits(UUID offerId, int units) { return offers.tryHoldUnits(offerId, units); }
    @Override public void releaseUnits(UUID offerId, int units) {
        if (units < 1) throw new IllegalArgumentException("units must be positive");
        if (!offers.releaseUnits(offerId, units)) {
            throw new IllegalStateException("Cannot release more units than are reserved.");
        }
    }
    @Override public boolean isReservable(UUID offerId, Instant now) {
        return offers.findById(offerId).filter(o -> o.isReservable(now, reservationCutoffMinutes))
                .filter(o -> merchants.isApproved(o.merchantId())).isPresent();
    }
    @Override public Optional<OfferSnapshot> snapshot(UUID offerId) {
        return offers.findById(offerId).map(o -> new OfferSnapshot(o.id(), o.merchantId(), o.priceMinor(),
                o.pickupStart(), o.pickupEnd(), o.status(), o.availableUnits()));
    }
}
