package ma.kulha.identity.infrastructure.security;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.UserRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * After the JWT signature, issuer, audience and expiry are validated, this loads the user from
 * PostgreSQL and takes the roles from there. A deleted or disabled account is refused immediately,
 * even if its access token has not expired yet.
 */
@Component
class DatabaseBackedJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;

    DatabaseBackedJwtConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
        } catch (RuntimeException e) {
            throw new InvalidBearerTokenException("Invalid token subject");
        }
        User user = users.findById(userId)
                .filter(found -> found.status() == UserStatus.ACTIVE)
                .orElseThrow(() -> new InvalidBearerTokenException("Unknown or disabled account"));

        List<GrantedAuthority> authorities = user.roles().stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, user.id().toString());
    }
}
