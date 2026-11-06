package ma.kulha.offer.domain.model;

public class StaleOfferVersionException extends RuntimeException {
    public StaleOfferVersionException() { super("This offer changed meanwhile. Reload and try again."); }
}
