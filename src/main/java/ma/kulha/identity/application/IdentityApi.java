package ma.kulha.identity.application;

import java.util.Optional;
import java.util.UUID;

/** Public entry point of the identity module. Other modules call only this. */
public interface IdentityApi {

    boolean isEmailVerified(UUID userId);

    Optional<String> emailOf(UUID userId);

    void grantMerchantRole(UUID userId);

    void revokeMerchantRole(UUID userId);
}
