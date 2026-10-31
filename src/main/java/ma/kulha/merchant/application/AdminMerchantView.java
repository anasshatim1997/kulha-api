package ma.kulha.merchant.application;

import ma.kulha.merchant.domain.model.Merchant;

/** A merchant with the owner's email, for administrators. */
public record AdminMerchantView(Merchant merchant, String ownerEmail) {
}
