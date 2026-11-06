package ma.kulha.offer.application;

public class OfferLimitExceededException extends RuntimeException { public OfferLimitExceededException() { super("The merchant has reached the limit of 50 active offers."); } }
