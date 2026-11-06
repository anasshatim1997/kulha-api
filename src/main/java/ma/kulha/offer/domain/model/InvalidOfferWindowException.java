package ma.kulha.offer.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class InvalidOfferWindowException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public InvalidOfferWindowException() {
        this(Map.of("pickupStart", "Must be in the future", "pickupEnd", "Must be after pickup start, within 24 hours and within 7 days"));
    }

    public InvalidOfferWindowException(Map<String, String> fieldErrors) {
        super("Pickup window is invalid.");
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> fieldErrors() { return fieldErrors; }
}
