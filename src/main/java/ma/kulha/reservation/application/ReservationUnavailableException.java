package ma.kulha.reservation.application;
public class ReservationUnavailableException extends RuntimeException {
    public ReservationUnavailableException(String message) { super(message); }
}
