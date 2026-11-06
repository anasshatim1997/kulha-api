package ma.kulha.offer.domain.model;

/** A domain validation failure for offer data supplied by a merchant. */
public final class InvalidOfferDataException extends IllegalArgumentException {
    public InvalidOfferDataException(String message) {
        super(message);
    }
}
