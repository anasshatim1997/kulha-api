package ma.kulha.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import ma.kulha.identity.application.OpaqueTokens;
import ma.kulha.identity.domain.model.EmailTokenType;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.EmailTokenRepository;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/** Pre-hijacking defence: confirming an address ends every session that existed before the confirmation. */
class EmailVerificationSessionIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    EmailTokenRepository emailTokens;

    @Test
    void verifyingTheEmailRevokesEarlierSessionsAndIssuesAFreshOne() throws Exception {
        User user = createUser("pending@example.com", false);
        MockHttpServletResponse earlierLogin = doPost("/api/v1/auth/login",
                "{\"email\":\"pending@example.com\",\"password\":\"%s\"}".formatted(PASSWORD), null);
        assertThat(earlierLogin.getStatus()).isEqualTo(200);
        String earlierRefreshToken = field(earlierLogin, "refreshToken");

        String verifyToken = OpaqueTokens.generate();
        Instant now = Instant.now();
        emailTokens.insert(UUID.randomUUID(), user.id(), EmailTokenType.VERIFY, OpaqueTokens.hash(verifyToken),
                now.plus(Duration.ofHours(1)), now);

        MockHttpServletResponse verified = doPost("/api/v1/auth/verify-email",
                "{\"token\":\"%s\"}".formatted(verifyToken), null);

        assertThat(verified.getStatus()).isEqualTo(200);
        String freshRefreshToken = field(verified, "refreshToken");
        assertThat(doPost("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(earlierRefreshToken), null)
                .getStatus()).as("the session from before the verification").isEqualTo(401);
        assertThat(doPost("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(freshRefreshToken), null)
                .getStatus()).as("the session issued by the verification").isEqualTo(200);
    }
}
