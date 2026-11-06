package ma.kulha.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.offer.application.OfferApi;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class OfferLifecycleIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    MerchantService merchantService;
    @Autowired
    OfferApi offerApi;

    @Test
    void merchantCanCreateEditPublishUnpublishAndCancelDraftWhileAdminCanSuspendAndReinstate() throws Exception {
        String merchantId = applyAs("lifecycle-owner@example.com", "Lifecycle Bakery");
        var admin = createUser("lifecycle-admin@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        String ownerToken = tokenForEmail("lifecycle-owner@example.com");
        String adminToken = tokenFor(admin);
        String futureStart = Instant.now().plusSeconds(3600).toString();
        String futureEnd = Instant.now().plusSeconds(7200).toString();
        String createBody = offerJson("Lifecycle basket", 3, 500, futureStart, futureEnd, null, null);

        var created = mvc.perform(post("/api/v1/merchants/me/offers")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(createBody)).andReturn().getResponse();
        assertThat(created.getStatus()).isEqualTo(201);
        UUID offerId = UUID.fromString(field(created, "id"));

        var edited = mvc.perform(put("/api/v1/merchants/me/offers/" + offerId)
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(offerJson("Lifecycle basket edited", 4, 500, futureStart, futureEnd, 0L, "Contains sesame")))
                .andReturn().getResponse();
        assertThat(edited.getStatus()).isEqualTo(200);
        assertThat(edited.getContentAsString()).contains("Lifecycle basket edited");

        assertThat(mvc.perform(post("/api/v1/merchants/me/offers/" + offerId + "/publish")
                .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/api/v1/merchants/me/offers/" + offerId + "/unpublish")
                .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/api/v1/merchants/me/offers/" + offerId + "/publish")
                .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse().getStatus()).isEqualTo(200);

        var missingReason = mvc.perform(post("/api/v1/admin/offers/" + offerId + "/suspend")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andReturn().getResponse();
        assertThat(missingReason.getStatus()).isEqualTo(422);
        assertThat(missingReason.getContentAsString()).contains("fieldErrors", "reason");

        var forbiddenAdminAction = mvc.perform(post("/api/v1/admin/offers/" + offerId + "/suspend")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"moderation\"}")).andReturn().getResponse();
        assertThat(forbiddenAdminAction.getStatus()).isEqualTo(403);

        var suspended = mvc.perform(post("/api/v1/admin/offers/" + offerId + "/suspend")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Food safety review\"}")).andReturn().getResponse();
        assertThat(suspended.getStatus()).isEqualTo(200);
        assertThat(suspended.getContentAsString()).contains("SUSPENDED");

        var reinstated = mvc.perform(post("/api/v1/admin/offers/" + offerId + "/reinstate")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Review completed\"}")).andReturn().getResponse();
        assertThat(reinstated.getStatus()).isEqualTo(200);
        assertThat(reinstated.getContentAsString()).contains("UNPUBLISHED");

        var history = doGet("/api/v1/admin/offers/" + offerId + "/history", adminToken);
        assertThat(history.getStatus()).isEqualTo(200);
        assertThat(history.getContentAsString()).contains("OFFER_PUBLISHED", "OFFER_UNPUBLISHED", "OFFER_SUSPENDED", "OFFER_REINSTATED");

        var duplicate = mvc.perform(post("/api/v1/merchants/me/offers/" + offerId + "/duplicate")
                .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse();
        assertThat(duplicate.getStatus()).isEqualTo(201);
        UUID duplicateId = UUID.fromString(field(duplicate, "id"));
        var cancelled = mvc.perform(delete("/api/v1/merchants/me/offers/" + duplicateId)
                .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse();
        assertThat(cancelled.getStatus()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", duplicateId)
                .query(String.class).single()).isEqualTo("CANCELLED");

        assertThat(jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type='OFFER' AND target_id=:id AND action='OFFER_QUANTITY_CHANGED'")
                .param("id", offerId.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type='OFFER' AND target_id=:id AND action='OFFER_FOOD_INFO_CHANGED'")
                .param("id", offerId.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type='OFFER' AND target_id=:id AND action='OFFER_PUBLISHED'")
                .param("id", offerId.toString()).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type='OFFER' AND target_id=:id AND action='OFFER_UNPUBLISHED'")
                .param("id", offerId.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type='OFFER' AND target_id=:id AND action='OFFER_SUSPENDED'")
                .param("id", offerId.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type='OFFER' AND target_id=:id AND action='OFFER_REINSTATED'")
                .param("id", offerId.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void pendingMerchantCannotCreateAnOfferAndApprovedMerchantReservationLocksPrice() throws Exception {
        String merchantId = applyAs("pending-owner@example.com", "Pending Bakery");
        String pendingToken = tokenForEmail("pending-owner@example.com");
        var pendingCreate = mvc.perform(post("/api/v1/merchants/me/offers")
                .header("Authorization", "Bearer " + pendingToken)
                .contentType(MediaType.APPLICATION_JSON).content(offerJson("Pending", 2, 500, null, null, null, null)))
                .andReturn().getResponse();
        assertThat(pendingCreate.getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM offer.offers").query(Long.class).single()).isZero();

        var admin = createUser("pending-admin@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        String ownerToken = tokenForEmail("pending-owner@example.com");
        String futureStart = Instant.now().plusSeconds(3600).toString();
        String futureEnd = Instant.now().plusSeconds(7200).toString();
        var created = mvc.perform(post("/api/v1/merchants/me/offers")
                .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(offerJson("Reserved basket", 2, 500, futureStart, futureEnd, null, null)))
                .andReturn().getResponse();
        UUID offerId = UUID.fromString(field(created, "id"));
        assertThat(mvc.perform(post("/api/v1/merchants/me/offers/" + offerId + "/publish")
                .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(offerApi.tryHoldUnits(offerId, 1)).isTrue();

        var lockedEdit = mvc.perform(put("/api/v1/merchants/me/offers/" + offerId)
                .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(offerJson("Reserved basket", 2, 600, futureStart, futureEnd, 1L, null)))
                .andReturn().getResponse();
        assertThat(lockedEdit.getStatus()).isEqualTo(422);
    }

    private static String offerJson(String title, int quantity, int price, String pickupStart,
                                    String pickupEnd, Long version, String foodNotes) {
        return """
                {"title":"%s","description":"Surplus food basket","category":"BREAD_PASTRY",
                 "priceMinor":%d,"originalPriceMinor":null,"quantityTotal":%d,
                 "pickupStart":%s,"pickupEnd":%s,"allergensStatus":"UNKNOWN","allergens":[],
                 "foodNotes":%s,"version":%s}
                """.formatted(title, price, quantity, json(pickupStart), json(pickupEnd), json(foodNotes), version == null ? "null" : version.toString());
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
