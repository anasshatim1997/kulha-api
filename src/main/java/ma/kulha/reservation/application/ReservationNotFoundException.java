package ma.kulha.reservation.application;
public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException() { super("Reservation not found."); }
}
