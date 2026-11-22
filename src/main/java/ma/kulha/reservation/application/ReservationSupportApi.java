package ma.kulha.reservation.application;

import java.util.Optional;
import java.util.UUID;

/** Narrow read-only contract for support workflows; reservation persistence stays module-owned. */
public interface ReservationSupportApi {
    Optional<ReservationReference> findByReference(String reference);

    record ReservationReference(String reference, UUID customerId, UUID merchantId, UUID offerId) {}
}
