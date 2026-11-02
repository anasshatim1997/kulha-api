package ma.kulha.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.domain.repository.ExternalIdentityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcExternalIdentityRepository implements ExternalIdentityRepository {
    private final JdbcClient jdbc;

    JdbcExternalIdentityRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<UUID> findUserId(String provider, String subject) {
        return jdbc.sql("SELECT user_id FROM identity.external_identities WHERE provider = :provider AND subject = :subject")
                .param("provider", provider).param("subject", subject).query(UUID.class).optional();
    }

    @Override
    public void insert(UUID id, UUID userId, String provider, String subject, String email) {
        jdbc.sql("""
                INSERT INTO identity.external_identities (id, user_id, provider, subject, email)
                VALUES (:id, :userId, :provider, :subject, :email)
                """)
                .param("id", id).param("userId", userId).param("provider", provider)
                .param("subject", subject).param("email", email).update();
    }
}
