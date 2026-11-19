package ma.kulha.reservation.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.reservation.domain.model.Reservation;
import ma.kulha.reservation.domain.model.ReservationFilter;
import ma.kulha.reservation.domain.model.ReservationStatus;

public interface ReservationRepository {
    void insert(Reservation reservation);
    Optional<Reservation> findByReference(String reference);
    Optional<Reservation> findByReferenceForUpdate(String reference);
    Optional<Reservation> findByPickupCredentialHashForUpdate(String hash);
    boolean savePickupCredential(UUID reservationId, String hash, Instant updatedAt);
    boolean updateStatus(Reservation reservation, ReservationStatus expectedStatus);
    void recordCancellation(Reservation reservation, String reasonCode, String comment, Instant cancelledAt);
    List<Reservation> list(UUID customerId, ReservationFilter filter, Instant now, int limit, int offset);
    long count(UUID customerId, ReservationFilter filter, Instant now);
    int expireActive(Instant now);
}
