package ma.kulha.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.time.Instant;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;

/**
 * Runs with the rate limiter switched on (it is off in all other tests).
 * Every test uses its own IP addresses and emails, because the counters live as long as this context.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.rate-limit.enabled=true", "app.rate-limit.bff-secret=test-bff-secret"})
class RateLimitIntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpServletResponse postFrom(String ip, String path, String json) throws Exception {
        return mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        }))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse loginFrom(String ip, String email) throws Exception {
        return postFrom(ip, "/api/v1/auth/login",
                "{\"email\":\"%s\",\"password\":\"whatever-password\"}".formatted(email));
    }

    private MockHttpServletResponse loginWithForwardedIp(
            String remoteIp, String forwardedIp, String secret, String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"whatever-password\"}".formatted(email))
                        .header("X-Kulha-Client-IP", forwardedIp)
                        .header("X-Kulha-Bff-Secret", secret)
                        .with(request -> {
                            request.setRemoteAddr(remoteIp);
                            return request;
                        }))
                .andReturn().getResponse();
    }

    @Test
    void loginIsLimitedPerTrustedForwardedIpAnd429CarriesRetryAfter() throws Exception {
        String remoteIp = "192.0.2.10";
        for (int i = 0; i < 10; i++) {
            assertThat(loginWithForwardedIp(remoteIp, "10.1.0.1", "test-bff-secret",
                    "ip-limit-" + i + "@example.com").getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse limited = loginWithForwardedIp(
                remoteIp, "10.1.0.1", "test-bff-secret", "ip-limit-extra@example.com");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(Objects.requireNonNull(limited.getHeader("Retry-After")))).isPositive();
        // A different client IP remains in its own bucket, even behind the same BFF.
        assertThat(loginWithForwardedIp(remoteIp, "10.1.0.2", "test-bff-secret",
                "someone@example.com").getStatus()).isEqualTo(401);
    }

    @Test
    void forwardedIpWithoutSecretIsIgnored() throws Exception {
        String remoteIp = "192.0.2.20";
        for (int i = 0; i < 10; i++) {
            assertThat(postWithForgedIp(remoteIp, "198.51.100." + (i + 1), null,
                    "forged-no-secret-" + i + "@example.com").getStatus()).isEqualTo(401);
        }
        assertThat(postWithForgedIp(remoteIp, "203.0.113.99", null,
                "forged-no-secret-extra@example.com").getStatus()).isEqualTo(429);
    }

    @Test
    void invalidForwardedIpFallsBackToRemoteAddressEvenWithValidSecret() throws Exception {
        String remoteIp = "192.0.2.22";
        for (int i = 0; i < 10; i++) {
            assertThat(loginWithForwardedIp(remoteIp, "not-an-ip-" + i, "test-bff-secret",
                    "invalid-forwarded-ip-" + i + "@example.com").getStatus()).isEqualTo(401);
        }
        assertThat(loginWithForwardedIp(remoteIp, "still-not-an-ip", "test-bff-secret",
                "invalid-forwarded-ip-extra@example.com").getStatus()).isEqualTo(429);
    }

    @Test
    void forwardedIpWithWrongSecretIsIgnored() throws Exception {
        String remoteIp = "192.0.2.21";
        for (int i = 0; i < 10; i++) {
            assertThat(postWithForgedIp(remoteIp, "198.51.100." + (i + 1), "wrong-secret",
                    "forged-wrong-secret-" + i + "@example.com").getStatus()).isEqualTo(401);
        }
        assertThat(postWithForgedIp(remoteIp, "203.0.113.99", "wrong-secret",
                "forged-wrong-secret-extra@example.com").getStatus()).isEqualTo(429);
    }

    private MockHttpServletResponse postWithForgedIp(
            String remoteIp, String forwardedIp, String secret, String email) throws Exception {
        var request = post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"whatever-password\"}".formatted(email))
                .header("X-Kulha-Client-IP", forwardedIp);
        if (secret != null) request.header("X-Kulha-Bff-Secret", secret);
        return mvc.perform(request.with(servletRequest -> {
                    servletRequest.setRemoteAddr(remoteIp);
                    return servletRequest;
                }))
                .andReturn().getResponse();
    }

    @Test
    void loginIsLimitedPerAccountEvenWhenTheAttemptsComeFromManyAddresses() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(loginFrom("10.2.0." + i, "victim@example.com").getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse limited = loginFrom("10.2.0.99", "victim@example.com");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(Objects.requireNonNull(limited.getHeader("Retry-After")))).isPositive();
        // Other accounts are not affected.
        assertThat(loginFrom("10.2.0.100", "someone-else@example.com").getStatus()).isEqualTo(401);
    }


    @Test
    void successfulLoginsDoNotConsumeTheAccountFailureBudget() throws Exception {
        String email = "success-does-not-count@example.com";
        User user = User.register(email, passwordEncoder.encode("a-long-enough-password"), "Test User", null, Instant.now());
        users.insert(user);
        users.markEmailVerified(user.id(), Instant.now());

        for (int i = 0; i < 10; i++) {
            int ipSuffix = 30 + i;
            var response = mvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"a-long-enough-password\"}")
                    .with(request -> { request.setRemoteAddr("192.0.2." + ipSuffix); return request; }))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        for (int i = 0; i < 10; i++) {
            assertThat(loginFrom("198.51.100." + (i + 1), email).getStatus()).isEqualTo(401);
        }
        MockHttpServletResponse limited = loginFrom("203.0.113.77", email);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void forgotPasswordIsLimitedPerIpAddress() throws Exception {
        String ip = "10.3.0.1";
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = postFrom(ip, "/api/v1/auth/forgot-password",
                    "{\"email\":\"forgot-" + i + "@example.com\"}");
            assertThat(response.getStatus()).isEqualTo(202);
        }

        MockHttpServletResponse limited = postFrom(ip, "/api/v1/auth/forgot-password",
                "{\"email\":\"forgot-extra@example.com\"}");

        assertThat(limited.getStatus()).isEqualTo(429);
    }
}
