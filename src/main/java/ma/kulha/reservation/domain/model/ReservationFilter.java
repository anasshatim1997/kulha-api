package ma.kulha.reservation.domain.model;
import java.util.Locale;
public enum ReservationFilter {
    UPCOMING, PAST, CANCELLED, ALL;
    public static ReservationFilter parse(String value) {
        if (value == null || value.isBlank()) return UPCOMING;
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("status must be upcoming, past, cancelled or all");
        }
    }
}
