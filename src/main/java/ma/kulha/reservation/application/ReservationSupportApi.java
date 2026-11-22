package ma.kulha.reservation.application;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Narrow read-only contract for support workflows; reservation persistence stays module-owned. */
public interface ReservationSupportApi {
    Optional<ReservationReference> findByReference(String reference);
    List<ReservationReference> findByReferences(Collection<String> references);

    record ReservationReference(String reference, UUID customerId, UUID merchantId, UUID offerId, String offerTitle, String merchantName) {}
}
