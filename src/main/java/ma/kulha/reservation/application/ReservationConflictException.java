package ma.kulha.reservation.application;
public class ReservationConflictException extends RuntimeException {
    public ReservationConflictException(String message) { super(message); }
}
