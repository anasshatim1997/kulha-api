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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(GoogleAuthenticationService.class);
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

    /**
     * Every rejection is the same 401 for the caller, but the reason code is logged (never the token, the
     * email or any secret) so an operator can tell a wrong client id from a wrong BFF secret or a bad nonce.
     */
    private static InvalidCredentialsException rejected(String reason) {
        log.warn("Google sign-in rejected: {}", reason);
        return new InvalidCredentialsException();
    }

    /** Google sends a JSON boolean; some libraries and proxies turn it into the string "true". */
    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof String text && "true".equalsIgnoreCase(text.trim()));
    }

    public TokenPair login(String idToken, String nonce, String suppliedBffSecret) {
        if (bffSecret.length == 0 || suppliedBffSecret == null || !MessageDigest.isEqual(
                bffSecret, suppliedBffSecret.getBytes(StandardCharsets.UTF_8))) {
            // Most often KULHA_BFF_SHARED_SECRET differs between the web app and the API, or is unset on one side.
            throw rejected(bffSecret.length == 0 ? "bff_secret_not_configured_on_api" : "bff_secret_mismatch");
        }
        if (clientId.isBlank()) {
            log.warn("Google sign-in unavailable: GOOGLE_OAUTH_CLIENT_ID is not configured on the API");
            throw new GoogleSignInUnavailableException();
        }
        final Jwt claims;
        try {
            claims = decoder.decode(idToken);
        } catch (JwtException | IllegalArgumentException invalid) {
            // The message names the failed check (signature, expiry, "aud claim is not valid" when
            // GOOGLE_OAUTH_CLIENT_ID differs from the web app's GOOGLE_CLIENT_ID) and never contains token values.
            String why = invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage();
            throw rejected("invalid_id_token: " + (why.length() > 200 ? why.substring(0, 200) : why));
        }
        String subject = claims.getSubject();
        String email = claims.getClaimAsString("email");
        String name = claims.getClaimAsString("name");
        Object verified = claims.getClaim("email_verified");
        String tokenNonce = claims.getClaimAsString("nonce");
        String authorizedParty = claims.getClaimAsString("azp");
        if (authorizedParty != null && !clientId.equals(authorizedParty)) {
            throw rejected("authorized_party_mismatch");
        }
        if (subject == null || subject.isBlank()) throw rejected("missing_subject");
        if (email == null || email.isBlank()) throw rejected("missing_email");
        if (!isTrue(verified)) throw rejected("email_not_verified_by_google");
        if (nonce == null || !MessageDigest.isEqual(
                nonce.getBytes(StandardCharsets.UTF_8), (tokenNonce == null ? "" : tokenNonce).getBytes(StandardCharsets.UTF_8))) {
            throw rejected("nonce_mismatch");
        }
        String normalizedEmail = User.normalizeEmail(email);
        if (normalizedEmail.length() > 254 || !normalizedEmail.contains("@")) {
            throw rejected("invalid_email");
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            UUID linkedUserId = identities.findUserId(PROVIDER, subject).orElse(null);
            User user;
            if (linkedUserId != null) {
                user = users.findById(linkedUserId).filter(found -> found.status() == UserStatus.ACTIVE)
                        .orElseThrow(() -> rejected("linked_account_disabled_or_missing"));
            } else {
                user = users.findByEmail(normalizedEmail).orElse(null);
                if (user == null) {
                    String displayName = name == null || name.isBlank() ? normalizedEmail.substring(0, normalizedEmail.indexOf('@')) : name.trim();
                    if (displayName.length() > 120) displayName = displayName.substring(0, 120).trim();
                    user = new User(UUID.randomUUID(), normalizedEmail, passwordEncoder.encode(OpaqueTokens.generate()),
                            displayName, null, UserStatus.ACTIVE, now, java.util.Set.of(ma.kulha.identity.domain.model.Role.CUSTOMER), now);
                    users.insert(user);
                } else {
                    if (user.status() != UserStatus.ACTIVE) throw rejected("account_disabled");
                    users.markEmailVerified(user.id(), now);
                    user = users.findById(user.id()).orElseThrow(InvalidCredentialsException::new);
                }
                identities.insert(UUID.randomUUID(), user.id(), PROVIDER, subject, normalizedEmail);
            }
            return authentication.issueExternalLogin(user);
        });
    }
}
