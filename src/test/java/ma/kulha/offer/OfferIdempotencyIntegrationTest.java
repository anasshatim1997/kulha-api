package ma.kulha.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.offer.application.OfferApplicationService;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class OfferIdempotencyIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    MerchantService merchantService;
    @Autowired
    OfferApplicationService offerService;

    @Test
    void createReplaysTheOriginalResponseAndRejectsAChangedRequestForTheSameKey() throws Exception {
        String ownerToken = approvedMerchantToken("idem-owner@example.com");
        String body = draftJson("Morning basket", 8);

        MockHttpServletResponse first = postOffer(body, ownerToken, "create-key-001");
        UUID firstId = UUID.fromString(field(first, "id"));
        var owner = users.findByEmail("idem-owner@example.com").orElseThrow();
        offerService.edit(owner.id(), firstId, 0, "Updated after create", "Surplus food basket",
                ma.kulha.offer.domain.model.OfferCategory.BREAD_PASTRY, 500, null, 8, null, null,
                ma.kulha.offer.domain.model.AllergensStatus.UNKNOWN, java.util.List.of(), null);
        MockHttpServletResponse retry = postOffer(body, ownerToken, "create-key-001");
        MockHttpServletResponse changed = postOffer(draftJson("Different basket", 8), ownerToken, "create-key-001");

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(retry.getContentAsString()).contains("Morning basket").doesNotContain("Updated after create");
        assertThat(changed.getStatus()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM offer.offers").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void duplicateReplaysOneDraftAndKeepsItsPickupWindowCleared() throws Exception {
        String ownerToken = approvedMerchantToken("duplicate-owner@example.com");
        MockHttpServletResponse original = postOffer(draftJson("Original basket", 4), ownerToken, "base-key-001");
        String offerId = field(original, "id");

        MockHttpServletResponse first = mvc.perform(post("/api/v1/merchants/me/offers/{id}/duplicate", offerId)
                .header("Authorization", "Bearer " + ownerToken)
                .header("Idempotency-Key", "duplicate-key-001")).andReturn().getResponse();
        MockHttpServletResponse retry = mvc.perform(post("/api/v1/merchants/me/offers/{id}/duplicate", offerId)
                .header("Authorization", "Bearer " + ownerToken)
                .header("Idempotency-Key", "duplicate-key-001")).andReturn().getResponse();

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(first.getContentAsString()).contains("\"pickupStart\":null", "\"pickupEnd\":null", "\"status\":\"DRAFT\"");
        assertThat(jdbc.sql("SELECT count(*) FROM offer.offers").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateExactlyOneOffer() throws Exception {
        String ownerEmail = "idem-concurrent-owner@example.com";
        approvedMerchantToken(ownerEmail);
        var owner = users.findByEmail(ownerEmail).orElseThrow();
        int requests = 12;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        String requestHash = "c".repeat(64);
        try {
            List<Future<UUID>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return offerService.create(owner.id(), "Concurrent basket", "Surplus food", OfferCategory.OTHER,
                            500, null, 4, null, null, AllergensStatus.UNKNOWN, List.of(), null,
                            "concurrent-key-001", requestHash).id();
                }));
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            UUID expected = futures.get(0).get();
            for (Future<UUID> future : futures) assertThat(future.get()).isEqualTo(expected);
            assertThat(jdbc.sql("SELECT count(*) FROM offer.offers").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM offer.idempotency_keys WHERE actor_user_id=:actor AND idempotency_key=:key")
                    .param("actor", owner.id()).param("key", "concurrent-key-001").query(Long.class).single()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private String approvedMerchantToken(String ownerEmail) throws Exception {
        String merchantId = applyAs(ownerEmail, "KULHA Test Bakery");
        var admin = createUser("admin-" + UUID.randomUUID() + "@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        return tokenForEmail(ownerEmail);
    }

    private MockHttpServletResponse postOffer(String body, String token, String key) throws Exception {
        return mvc.perform(post("/api/v1/merchants/me/offers")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse();
    }

    private static String draftJson(String title, int quantity) {
        return """
                {"title":"%s","description":"Surplus food basket","category":"BREAD_PASTRY",
                 "priceMinor":500,"originalPriceMinor":null,"quantityTotal":%d,
                 "pickupStart":null,"pickupEnd":null,"allergensStatus":"UNKNOWN","allergens":[],"foodNotes":null}
                """.formatted(title, quantity);
    }
}
