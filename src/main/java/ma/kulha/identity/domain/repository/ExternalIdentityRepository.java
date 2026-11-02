package ma.kulha.identity.domain.repository;

import java.util.Optional;
import java.util.UUID;

public interface ExternalIdentityRepository {
    Optional<UUID> findUserId(String provider, String subject);
    void insert(UUID id, UUID userId, String provider, String subject, String email);
}
