package ma.kulha.offer.domain.repository;

import java.util.Optional;
import java.util.UUID;
import ma.kulha.offer.domain.model.Offer;

public interface OfferIdempotencyRepository {
    void lock(UUID actorUserId, String key);
    Optional<IdempotencyRecord> find(UUID actorUserId, String key);
    void save(UUID actorUserId, String key, String requestHash, UUID offerId);

    record IdempotencyRecord(String requestHash, Offer responseSnapshot) { }
}
