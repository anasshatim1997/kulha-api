package ma.kulha.reservation.domain.repository;
import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.reservation.domain.model.Reservation;
import ma.kulha.reservation.domain.model.ReservationFilter;
import ma.kulha.reservation.domain.model.ReservationStatus;
public interface ReservationRepository {
    void insert(Reservation reservation);
    Optional<Reservation> findByReference(String reference);
    List<Reservation> findByReferences(Collection<String> references);
    Optional<Reservation> findByReferenceForUpdate(String reference);
    Optional<Reservation> findByPickupCredentialHashForUpdate(String tokenHash, Instant now);
    void insertPickupCredential(UUID reservationId, String tokenHash, Instant expiresAt, Instant createdAt);
    void markPickupCredentialsUsed(UUID reservationId, Instant usedAt);
    void recordCancellationEvent(Reservation reservation, String reasonCode, String comment, UUID actorId, Instant occurredAt);
    boolean updateStatus(Reservation reservation, ReservationStatus expectedStatus);
    List<Reservation> list(UUID customerId, ReservationFilter filter, Instant now, int limit, int offset);
    long count(UUID customerId, ReservationFilter filter, Instant now);
    int expireActive(Instant now);
}
