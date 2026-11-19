package ma.kulha.reservation.domain.model;

import java.util.Locale;

public enum ReservationCancellationReason {
    NOT_SPECIFIED,
    PICKUP_TIME,
    TOO_FAR,
    CHANGED_MIND,
    FOUND_ALTERNATIVE,
    OFFER_EXPECTATION,
    BOOKED_BY_MISTAKE,
    OTHER;

    public static ReservationCancellationReason parse(String value) {
        if (value == null || value.isBlank()) return NOT_SPECIFIED;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unsupported cancellation reason.");
        }
    }
}
