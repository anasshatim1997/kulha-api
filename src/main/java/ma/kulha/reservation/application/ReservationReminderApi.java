package ma.kulha.reservation.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only reservation-owned pickup candidates for notification scheduling. */
public interface ReservationReminderApi {
    List<PickupReminderCandidate> findConfirmedUpcoming(Instant now, Instant pickupBefore,
            Instant afterPickupStart, UUID afterReservationId, int limit);

    record PickupReminderCandidate(UUID reservationId, UUID customerId, String reference,
                                   String merchantName, Instant pickupStart) {}
}
