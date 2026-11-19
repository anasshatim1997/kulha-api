package ma.kulha.reservation.application;
public class InvalidReservationRequestException extends RuntimeException {
    public InvalidReservationRequestException(String message) { super(message); }
}
