package ma.kulha.reservation.domain.model;

import java.util.Locale;

public enum CancellationReasonCode {
    PICKUP_TIME,
    TOO_FAR,
    CHANGED_MIND,
    FOUND_ALTERNATIVE,
    OFFER_NOT_AS_EXPECTED,
    RESERVED_BY_MISTAKE,
    OTHER,
    NOT_PROVIDED;

    public static CancellationReasonCode parse(String value) {
        if (value == null || value.isBlank()) return NOT_PROVIDED;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported cancellation reason code.");
        }
    }
}
