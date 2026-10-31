package ma.kulha.merchant.application;

public class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException() {
        super("Merchant not found");
    }
}
