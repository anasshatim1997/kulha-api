package ma.kulha.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.offer.application.OfferApplicationService;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.OfferCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class SupportCaseHistoricalReferenceIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired MerchantService merchantService;
    @Autowired OfferApplicationService offerApplicationService;

    @Test
    void suspendedShopAndOfferRemainVisibleAndActionsFailGracefully() throws Exception {
        User customer = createUser("historical-case-customer@example.com", true);
        User owner = createUser("historical-case-owner@example.com", true);
        User admin = createUser("historical-case-admin@example.com", true, Role.ADMIN);
        Merchant merchant = merchantService.apply(owner.id(), new Merchant.Profile(
                "Historical Bakery", BusinessType.BAKERY, "12 rue Atlas", "Casablanca",
                "+212600000091", "Lun-Sam 8h-18h"));
        merchantService.approve(admin.id(), merchant.id());
        var offer = offerApplicationService.create(owner.id(), "Historical basket", "Description",
                OfferCategory.OTHER, 500, null, 2, Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200), AllergensStatus.UNKNOWN, List.of(), null);
        offerApplicationService.publish(owner.id(), offer.id());
        String reference = "KLH-C-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var now = Instant.now().atOffset(java.time.ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO admin.support_cases
                    (id, reference, category, subject, description, priority, status, customer_user_id,
                     merchant_id, offer_id, created_at, updated_at)
                VALUES (:id, :reference, 'FOOD_SAFETY', 'Historical report', 'Reported basket',
                        'HIGH', 'OPEN', :customer, :merchant, :offer, :now, :now)
                """).param("id", UUID.randomUUID()).param("reference", reference)
                .param("customer", customer.id()).param("merchant", merchant.id())
                .param("offer", offer.id()).param("now", now).update();

        merchantService.suspend(admin.id(), merchant.id(), "Shop temporarily suspended");

        mvc.perform(get("/api/v1/admin/support-cases/" + reference)
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.merchantName").value("Historical Bakery"))
                .andExpect(jsonPath("$.summary.offerTitle").value("Historical basket"));

        mvc.perform(post("/api/v1/admin/support-cases/" + reference + "/actions")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONTACT_SHOP\",\"reason\":\"Shop is suspended\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/admin/support-cases/" + reference + "/actions")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"KEEP_OFFER_HIDDEN\",\"reason\":\"Keep historical report hidden\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT hidden_offer FROM admin.support_cases WHERE reference=:reference")
                .param("reference", reference).query(Boolean.class).single()).isTrue();
    }
}
