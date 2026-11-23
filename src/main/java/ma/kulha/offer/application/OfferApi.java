package ma.kulha.offer.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferApi {
    boolean tryHoldUnits(UUID offerId, int units);
    void releaseUnits(UUID offerId, int units);
    boolean isReservable(UUID offerId, Instant now);
    Optional<OfferSnapshot> snapshot(UUID offerId);

    /** Batch-resolves historical offer references without filtering by publication status. */
    List<SupportOfferProfile> findSupportProfiles(Collection<UUID> offerIds);
    MerchantDashboardOffers merchantDashboard(UUID merchantId, int limit);
    void suspendForFoodSafety(UUID actorId, UUID offerId, String reason);

    record SupportOfferProfile(UUID id, UUID merchantId, String title, String status) {}
    record MerchantOfferRow(UUID id, String title, int priceMinor, int quantityTotal, int quantityReserved, String status) {}
    record MerchantDashboardOffers(long liveOffers, List<MerchantOfferRow> offers) {}
}
