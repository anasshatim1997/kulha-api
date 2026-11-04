package ma.kulha.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/** The HTTP edge of POST /api/v1/auth/google: only the BFF may call it, and it fails closed. */
@TestPropertySource(properties = {
        "app.rate-limit.bff-secret=endpoint-test-secret",
        "app.security.google.client-id="
})
class GoogleLoginEndpointIntegrationTest extends AbstractApiIntegrationTest {

    private static final String BODY = "{\"idToken\":\"not-a-real-token\",\"nonce\":\"some-nonce\"}";

    private MockHttpServletResponse call(String secretHeaderOrNull) throws Exception {
        var request = post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON).content(BODY);
        if (secretHeaderOrNull != null) {
            request.header("X-Kulha-Bff-Secret", secretHeaderOrNull);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    @Test
    void aCallWithoutTheBffSecretIsRefusedWith401() throws Exception {
        assertThat(call(null).getStatus()).isEqualTo(401);
    }

    @Test
    void aCallWithAWrongBffSecretIsRefusedWith401() throws Exception {
        assertThat(call("not-the-secret").getStatus()).isEqualTo(401);
    }

    @Test
    void withTheRightSecretButNoGoogleClientIdTheAnswerIs503() throws Exception {
        assertThat(call("endpoint-test-secret").getStatus()).isEqualTo(503);
    }

    @Test
    void anInvalidBodyIsA400BeforeAnythingElseHappens() throws Exception {
        var request = post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-Kulha-Bff-Secret", "endpoint-test-secret");
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(400);
    }
}
