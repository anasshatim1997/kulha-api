package ma.kulha.offer.domain.model;

public class LockedOfferFieldsException extends RuntimeException {
    public LockedOfferFieldsException() { super("Price and pickup window are locked after the first reservation."); }
}
