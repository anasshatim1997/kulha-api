package ma.kulha.offer.application;

public class MerchantNotApprovedException extends RuntimeException { public MerchantNotApprovedException() { super("An approved merchant account is required."); } }
