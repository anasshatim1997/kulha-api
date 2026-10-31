package ma.kulha.merchant.application;

public class MerchantAlreadyExistsException extends RuntimeException {

    public MerchantAlreadyExistsException() {
        super("This account already has a merchant");
    }
}
