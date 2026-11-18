package ma.kulha.merchant.application;

import java.util.UUID;

/** Published after a shop's editable profile has been saved. */
public record MerchantProfileUpdated(UUID merchantId) { }
