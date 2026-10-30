package ma.kulha.identity.application;

import java.time.Instant;
import java.util.UUID;

/** Creates a signed access token for a user. Implemented in the infrastructure layer. */
public interface AccessTokenIssuer {

    AccessToken issue(UUID userId, Instant now);
}
