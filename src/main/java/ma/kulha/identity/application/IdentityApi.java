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

    /** Batch-resolves masked display data; deliberately excludes email and phone. */
    List<SupportUserSummary> findSupportSummaries(Collection<UUID> userIds);
    Optional<SupportUserSummary> findSupportSummary(UUID userId);

    /** PII-bearing lookup; callers must authorize disclosure before invoking it. */
    Optional<SupportUserDetails> findSupportDetails(UUID userId);

    /** Active role names without exposing identity-domain types across module boundaries. */
    Set<String> findActiveRoleNames(UUID userId);

    record SupportUserSummary(UUID id, String fullName, boolean active) {}
    record SupportUserDetails(UUID id, String fullName, String email, String phone, boolean active) {}
}
