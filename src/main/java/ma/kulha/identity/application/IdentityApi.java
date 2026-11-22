package ma.kulha.identity.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Public entry point of the identity module. Other modules call only this. */
public interface IdentityApi {

    boolean isEmailVerified(UUID userId);

    Optional<String> emailOf(UUID userId);

    void grantMerchantRole(UUID userId);

    void revokeMerchantRole(UUID userId);

    /** Batch-resolves historical support references without requiring the account to remain active. */
    List<SupportUserProfile> findSupportProfiles(Collection<UUID> userIds);

    /** Resolves a single identity for an explicitly authorized PII disclosure. */
    Optional<SupportUserProfile> findSupportProfile(UUID userId);

    /** Active role names without exposing identity-domain types across module boundaries. */
    Set<String> findActiveRoleNames(UUID userId);

    record SupportUserProfile(UUID id, String fullName, String email, String phone, boolean active) {}
}
