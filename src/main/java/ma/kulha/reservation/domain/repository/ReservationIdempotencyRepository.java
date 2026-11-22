package ma.kulha.reservation.domain.repository;

import java.util.Optional;
import java.util.UUID;

public interface ReservationIdempotencyRepository {
    void lock(UUID customerId, String key);
    Optional<IdempotencyRecord> find(UUID customerId, String key);
    void save(UUID customerId, String key, String requestHash, UUID reservationId, String responseSnapshot);

    record IdempotencyRecord(String requestHash, String responseSnapshot) { }
}
