package ma.kulha.offer.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.model.OfferStatus;
import ma.kulha.offer.domain.model.OfferSummary;

public interface OfferRepository {
    void insert(Offer offer);
    Optional<Offer> findById(UUID id);
    Optional<Offer> findByIdForUpdate(UUID id);
    List<OfferSummary> listByMerchant(UUID merchantId, OfferStatus status, int limit, int offset);
    long countByMerchant(UUID merchantId, OfferStatus status);
    long countActiveByMerchant(UUID merchantId);
    boolean update(Offer offer, long expectedVersion);
    boolean tryHoldUnits(UUID offerId, int units);
    boolean releaseUnits(UUID offerId, int units);
    List<Offer> expireBatch(Instant now, int limit);
    List<Offer> findPublishedByMerchant(UUID merchantId);
}
