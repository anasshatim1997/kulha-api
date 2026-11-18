package ma.kulha.reservation.domain.model;
public class InvalidReservationStateException extends RuntimeException {
    public InvalidReservationStateException(String message) { super(message); }
}
