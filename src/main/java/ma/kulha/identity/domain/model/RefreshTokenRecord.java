package ma.kulha.identity.domain.model;

import java.util.UUID;

/** What the rest of the code needs to know about a refresh token that was just used. */
public record RefreshTokenRecord(UUID id, UUID userId, UUID familyId) {
}
