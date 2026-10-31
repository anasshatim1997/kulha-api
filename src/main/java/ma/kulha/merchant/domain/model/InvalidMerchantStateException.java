package ma.kulha.merchant.domain.model;

/** The requested change is not allowed from the merchant's current status. */
public class InvalidMerchantStateException extends RuntimeException {

    public InvalidMerchantStateException(String message) {
        super(message);
    }
}
