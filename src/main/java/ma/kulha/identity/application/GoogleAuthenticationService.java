package ma.kulha.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.model.UserStatus;
import ma.kulha.identity.domain.repository.ExternalIdentityRepository;
import ma.kulha.identity.domain.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GoogleAuthenticationService {
    private static final String PROVIDER = "GOOGLE";
    private final JwtDecoder decoder;
    private final UserRepository users;
    private final ExternalIdentityRepository identities;
    private final AuthenticationService authentication;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final byte[] bffSecret;
    private final String clientId;

    GoogleAuthenticationService(@Qualifier("googleIdTokenDecoder") JwtDecoder decoder, UserRepository users,
            ExternalIdentityRepository identities, AuthenticationService authentication,
            PasswordEncoder passwordEncoder, TransactionTemplate tx, Clock clock,
            @Value("${app.rate-limit.bff-secret:}") String bffSecret,
            @Value("${app.security.google.client-id:}") String clientId) {
        this.decoder = decoder; this.users = users; this.identities = identities;
        this.authentication = authentication; this.passwordEncoder = passwordEncoder;
        this.tx = tx; this.clock = clock; this.bffSecret = bffSecret.getBytes(StandardCharsets.UTF_8);
        this.clientId = clientId;
    }

    public TokenPair login(String idToken, String nonce, String suppliedBffSecret) {
        if (bffSecret.length == 0 || suppliedBffSecret == null || !MessageDigest.isEqual(
                bffSecret, suppliedBffSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidCredentialsException();
        }
        if (clientId.isBlank()) {
            throw new GoogleSignInUnavailableException();
        }
        final Jwt claims;
        try {
            claims = decoder.decode(idToken);
        } catch (JwtException | IllegalArgumentException invalid) {
            throw new InvalidCredentialsException();
        }
        String subject = claims.getSubject();
        String email = claims.getClaimAsString("email");
        String name = claims.getClaimAsString("name");
        Boolean verified = claims.getClaim("email_verified");
        String tokenNonce = claims.getClaimAsString("nonce");
        String authorizedParty = claims.getClaimAsString("azp");
        if (authorizedParty != null && !clientId.equals(authorizedParty)) {
            throw new InvalidCredentialsException();
        }
        if (subject == null || subject.isBlank() || email == null || email.isBlank()
                || !Boolean.TRUE.equals(verified) || nonce == null || !MessageDigest.isEqual(
                nonce.getBytes(StandardCharsets.UTF_8), (tokenNonce == null ? "" : tokenNonce).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidCredentialsException();
        }
        String normalizedEmail = User.normalizeEmail(email);
        if (normalizedEmail.length() > 254 || !normalizedEmail.contains("@")) {
            throw new InvalidCredentialsException();
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            UUID linkedUserId = identities.findUserId(PROVIDER, subject).orElse(null);
            User user;
            if (linkedUserId != null) {
                user = users.findById(linkedUserId).filter(found -> found.status() == UserStatus.ACTIVE)
                        .orElseThrow(InvalidCredentialsException::new);
            } else {
                user = users.findByEmail(normalizedEmail).orElse(null);
                if (user == null) {
                    String displayName = name == null || name.isBlank() ? normalizedEmail.substring(0, normalizedEmail.indexOf('@')) : name.trim();
                    if (displayName.length() > 120) displayName = displayName.substring(0, 120).trim();
                    user = new User(UUID.randomUUID(), normalizedEmail, passwordEncoder.encode(OpaqueTokens.generate()),
                            displayName, null, UserStatus.ACTIVE, now, java.util.Set.of(ma.kulha.identity.domain.model.Role.CUSTOMER), now);
                    users.insert(user);
                } else {
                    if (user.status() != UserStatus.ACTIVE) throw new InvalidCredentialsException();
                    users.markEmailVerified(user.id(), now);
                    user = users.findById(user.id()).orElseThrow(InvalidCredentialsException::new);
                }
                identities.insert(UUID.randomUUID(), user.id(), PROVIDER, subject, normalizedEmail);
            }
            return authentication.issueExternalLogin(user);
        });
    }
}
