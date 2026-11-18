package ma.kulha.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.rate-limit.enabled=true")
class PublicOfferRateLimitIntegrationTest extends AbstractApiIntegrationTest {
    @Test
    void publicSearchIsRateLimitedAndIncludesRetryAfter() throws Exception {
        for (int i = 0; i < 60; i++) {
            var response = mvc.perform(get("/api/v1/offers")
                    .with(request -> { request.setRemoteAddr("192.0.2.66"); return request; }))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
        }
        var limited = mvc.perform(get("/api/v1/offers")
                .with(request -> { request.setRemoteAddr("192.0.2.66"); return request; }))
                .andReturn().getResponse();
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isPositive();
        assertThat(limited.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void nearbySearchHasItsOwnPerIpRateLimit() throws Exception {
        String body = "{\"latitude\":33.59,\"longitude\":-7.62,\"radiusMeters\":5000}";
        for (int i = 0; i < 30; i++) {
            var response = mvc.perform(post("/api/v1/offers/nearby").contentType("application/json").content(body)
                    .with(request -> { request.setRemoteAddr("192.0.2.68"); return request; }))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
        }
        var limited = mvc.perform(post("/api/v1/offers/nearby").contentType("application/json").content(body)
                .with(request -> { request.setRemoteAddr("192.0.2.68"); return request; }))
                .andReturn().getResponse();
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isPositive();
        assertThat(limited.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void publicAutocompleteSuggestionsHaveTheirOwnPerIpRateLimit() throws Exception {
        for (int i = 0; i < 90; i++) {
            var response = mvc.perform(get("/api/v1/offers/suggestions")
                    .param("q", "br")
                    .with(request -> { request.setRemoteAddr("192.0.2.67"); return request; }))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
        }
        var limited = mvc.perform(get("/api/v1/offers/suggestions")
                .param("q", "br")
                .with(request -> { request.setRemoteAddr("192.0.2.67"); return request; }))
                .andReturn().getResponse();
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isPositive();
        assertThat(limited.getContentAsString()).contains("RATE_LIMITED");
    }

}
