package ma.kulha.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import ma.kulha.offer.domain.event.OfferEventEnvelope;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessException;

class OfferDiscoveryIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired MerchantService merchantService;
    @Autowired ApplicationEventPublisher events;

    @Test
    void onlyTheTwoPublicOfferGetRoutesAreAnonymous() throws Exception {
        assertThat(mvc.perform(get("/api/v1/offers")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/v1/offers/00000000-0000-0000-0000-000000000001"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/offers"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/merchants/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void publicListFiltersByLocationAndReturnsSqlComputedAvailabilityWithoutPrivateMerchantData() throws Exception {
        String merchantId = approvedMerchant("discovery-owner@example.com", "Discovery Bakery", "Maarif");
        String ownerToken = tokenForEmail("discovery-owner@example.com");
        UUID offerId = createPublishedOffer(ownerToken, "Bread basket", 4, 500);

        var response = mvc.perform(get("/api/v1/offers")
                .param("city", "casablanca")
                .param("district", "MAARIF")
                .param("availableOnly", "true"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("Bread basket", "Discovery Bakery", "Maarif", "availableUnits\":4", "publishedAt");
        assertThat(response.getContentAsString()).doesNotContain("ownerUserId", "ownerEmail", "decisionReason", "phone");
        assertThat(jdbc.sql("SELECT quantity_total - quantity_reserved FROM offer.offers WHERE id=:id")
                .param("id", offerId).query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void nearbySearchFiltersByRadiusAndReturnsNearestFirstWithRoundedDistances() throws Exception {
        approvedMerchantWithCoordinates("near-owner@example.com", "Nearby Bakery", "Maarif", 33.5895, -7.6200);
        approvedMerchantWithCoordinates("mid-owner@example.com", "Midrange Bakery", "Gauthier", 33.6000, -7.6200);
        approvedMerchantWithCoordinates("far-owner@example.com", "Far Bakery", "Anfa", 33.7000, -7.6200);
        UUID nearby = createPublishedOffer(tokenForEmail("near-owner@example.com"), "Nearest basket", 3, 500);
        UUID midrange = createPublishedOffer(tokenForEmail("mid-owner@example.com"), "Midrange basket", 3, 600);
        createPublishedOffer(tokenForEmail("far-owner@example.com"), "Outside radius basket", 3, 700);

        var response = mvc.perform(post("/api/v1/offers/nearby")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":33.5890,\"longitude\":-7.6200,\"radiusMeters\":2000,\"availableOnly\":true,\"page\":0,\"size\":20}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        String body = response.getContentAsString();
        assertThat(body).contains("Nearest basket", "Midrange basket", "distanceKm",
                        "\"latitude\":33.5895", "\"longitude\":-7.62")
                .doesNotContain("Outside radius basket");
        assertThat(body.indexOf(nearby.toString())).isLessThan(body.indexOf(midrange.toString()));
    }

    @Test
    void nearbySearchRespectsOptionalCityAndDistrictFilters() throws Exception {
        approvedMerchantWithCoordinates("map-maarif@example.com", "Maarif Map Bakery", "Maarif", 33.5895, -7.6200);
        approvedMerchantWithCoordinates("map-gauthier@example.com", "Gauthier Map Bakery", "Gauthier", 33.5900, -7.6210);
        createPublishedOffer(tokenForEmail("map-maarif@example.com"), "Maarif offer", 3, 500);
        createPublishedOffer(tokenForEmail("map-gauthier@example.com"), "Gauthier offer", 3, 600);

        var response = mvc.perform(post("/api/v1/offers/nearby")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":33.5890,\"longitude\":-7.6200,\"radiusMeters\":2000,\"city\":\"Casablanca\",\"district\":\"Maarif\",\"page\":0,\"size\":50}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("Maarif offer").doesNotContain("Gauthier offer");
    }

    @Test
    void nearbySearchRequiresValidCustomerCoordinates() throws Exception {
        var response = mvc.perform(post("/api/v1/offers/nearby")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":null,\"longitude\":-7.62}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        var outOfRange = mvc.perform(post("/api/v1/offers/nearby")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":100,\"longitude\":-7.62}"))
                .andReturn().getResponse();
        assertThat(outOfRange.getStatus()).isEqualTo(400);
    }

    @Test
    void publicDetailReturnsOnlyPublishedAvailableOffersAndPickupInformation() throws Exception {
        String merchantId = approvedMerchant("detail-owner@example.com", "Detail Bakery", "Gauthier");
        UUID offerId = createPublishedOffer(tokenForEmail("detail-owner@example.com"), "Detail basket", 2, 700);

        var response = mvc.perform(get("/api/v1/offers/" + offerId)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("Detail Bakery", "Casablanca", "Gauthier", "Pickup at the side counter", "UNKNOWN");
        assertThat(response.getContentAsString()).doesNotContain("ownerUserId", "ownerEmail", "decisionReason", "phone", "internalNote");
    }


    @Test
    void publicSearchMatchesOfferTitleAndDescriptionCaseInsensitively() throws Exception {
        approvedMerchant("search-owner@example.com", "Search Bakery", "Maarif");
        String ownerToken = tokenForEmail("search-owner@example.com");
        UUID titleMatch = createPublishedOffer(ownerToken, "Msemen basket", 3, 500);
        UUID descriptionMatch = createPublishedOffer(ownerToken, "Evening surprise", 2, 700);
        createPublishedOffer(ownerToken, "Fresh juice", 2, 600);
        jdbc.sql("UPDATE offer.offers SET description='Fresh traditional pancakes with honey' WHERE id=:id")
                .param("id", descriptionMatch).update();
        refreshOfferSearch(descriptionMatch);

        var titleResponse = mvc.perform(get("/api/v1/offers").param("q", "  MSEMEN  ")).andReturn().getResponse();
        var descriptionResponse = mvc.perform(get("/api/v1/offers").param("q", "traditional pancakes")).andReturn().getResponse();
        var emptyResponse = mvc.perform(get("/api/v1/offers").param("q", "nonexistent-basket")).andReturn().getResponse();
        var merchantResponse = mvc.perform(get("/api/v1/offers").param("q", "search bakery")).andReturn().getResponse();

        assertThat(titleResponse.getStatus()).isEqualTo(200);
        assertThat(titleResponse.getContentAsString()).contains("Msemen basket").doesNotContain("Evening surprise", "Fresh juice");
        assertThat(descriptionResponse.getContentAsString()).contains("Evening surprise").doesNotContain("Msemen basket", "Fresh juice");
        assertThat(emptyResponse.getStatus()).isEqualTo(200);
        assertThat(emptyResponse.getContentAsString()).contains("\"total\":0");
        assertThat(merchantResponse.getStatus()).isEqualTo(200);
        assertThat(merchantResponse.getContentAsString()).contains("Msemen basket", "Evening surprise", "Fresh juice");
    }

    @Test
    void publicSearchRejectsQueriesLongerThanEightyCharacters() throws Exception {
        var response = mvc.perform(get("/api/v1/offers").param("q", "a".repeat(81))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void draftExpiredSuspendedAndSoldOutOffersAreNotDiscoverable() throws Exception {
        String merchantId = approvedMerchant("hidden-owner@example.com", "Hidden Bakery", "Anfa");
        String ownerToken = tokenForEmail("hidden-owner@example.com");
        UUID draftId = createOffer(ownerToken, "Draft basket", 2, 500, false);
        UUID expiredId = createPublishedOffer(ownerToken, "Expired basket", 2, 500);
        UUID soldOutId = createPublishedOffer(ownerToken, "Sold out basket", 1, 500);
        UUID suspendedId = createPublishedOffer(ownerToken, "Suspended basket", 2, 500);
        jdbc.sql("UPDATE offer.offers SET pickup_end=now() - interval '2 hours', pickup_start=now() - interval '3 hours' WHERE id=:id")
                .param("id", expiredId).update();
        jdbc.sql("UPDATE offer.offers SET quantity_reserved=1, reservation_started_at=now() WHERE id=:id")
                .param("id", soldOutId).update();
        refreshOfferSearch(expiredId);
        refreshOfferSearch(soldOutId);

        var admin = createUser("hidden-admin@example.com", true, Role.ADMIN);
        merchantService.suspend(admin.id(), UUID.fromString(merchantId), "Operational review");

        String list = mvc.perform(get("/api/v1/offers")).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("Draft basket", "Expired basket", "Sold out basket", "Suspended basket");
        assertThat(mvc.perform(get("/api/v1/offers/" + draftId)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/offers/" + expiredId)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/offers/" + soldOutId)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/offers/" + suspendedId)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT status FROM merchant.merchants WHERE id=:id").param("id", UUID.fromString(merchantId))
                .query(String.class).single()).isEqualTo("SUSPENDED");
        assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", suspendedId)
                .query(String.class).single()).isEqualTo("SUSPENDED");
    }

    @Test
    void merchantSuspensionAndOfferCascadeRollBackInOneTransaction() throws Exception {
        String merchantId = approvedMerchant("rollback-owner@example.com", "Rollback Bakery", "Oasis");
        UUID offerId = createPublishedOffer(tokenForEmail("rollback-owner@example.com"), "Rollback basket", 2, 500);
        var admin = createUser("rollback-admin@example.com", true, Role.ADMIN);
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION admin.fail_offer_suspension_for_test() RETURNS trigger AS $$
                BEGIN
                    IF NEW.target_type='OFFER' AND NEW.action='OFFER_SUSPENDED' THEN
                        RAISE EXCEPTION 'forced offer audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("CREATE TRIGGER fail_offer_suspension_for_test BEFORE INSERT ON admin.audit_log FOR EACH ROW EXECUTE FUNCTION admin.fail_offer_suspension_for_test()").update();
        try {
            assertThatThrownBy(() -> merchantService.suspend(admin.id(), UUID.fromString(merchantId), "Operational review"))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.sql("SELECT status FROM merchant.merchants WHERE id=:id").param("id", UUID.fromString(merchantId))
                    .query(String.class).single()).isEqualTo("APPROVED");
            assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", offerId)
                    .query(String.class).single()).isEqualTo("PUBLISHED");
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS fail_offer_suspension_for_test ON admin.audit_log").update();
            jdbc.sql("DROP FUNCTION IF EXISTS admin.fail_offer_suspension_for_test()").update();
        }
    }

    @Test
    void unknownSortAndInvalidLocationFiltersAreRejected() throws Exception {
        var sort = mvc.perform(get("/api/v1/offers").param("sort", "distance")).andReturn().getResponse();
        var district = mvc.perform(get("/api/v1/offers").param("district", " ")).andReturn().getResponse();
        assertThat(sort.getStatus()).isEqualTo(400);
        assertThat(sort.getContentAsString()).contains("INVALID_REQUEST");
        assertThat(district.getStatus()).isEqualTo(400);
    }

    @Test
    void publicListPaginationIsStableCappedAndDefaultsToAvailableOnly() throws Exception {
        String merchantId = approvedMerchant("page-owner@example.com", "Page Bakery", "Centre");
        String owner = tokenForEmail("page-owner@example.com");
        createPublishedOffer(owner, "First basket", 2, 500);
        createPublishedOffer(owner, "Second basket", 2, 600);
        var first = mvc.perform(get("/api/v1/offers").param("page", "0").param("size", "1"))
                .andReturn().getResponse();
        var second = mvc.perform(get("/api/v1/offers").param("page", "1").param("size", "1"))
                .andReturn().getResponse();
        var capped = mvc.perform(get("/api/v1/offers").param("size", "500"))
                .andReturn().getResponse();
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(first.getContentAsString()).isNotEqualTo(second.getContentAsString());
        assertThat(capped.getContentAsString()).contains("\"size\":50");
    }


    @Test
    void publicSuggestionsReturnOnlyPublishedAvailableOffersFromApprovedMerchants() throws Exception {
        approvedMerchant("suggest-owner@example.com", "Msemen & Co Bakery", "Maarif");
        String ownerToken = tokenForEmail("suggest-owner@example.com");
        UUID available = createPublishedOffer(ownerToken, "Msemen breakfast basket", 3, 650);
        UUID soldOut = createPublishedOffer(ownerToken, "Msemen sold out basket", 1, 500);
        UUID expired = createPublishedOffer(ownerToken, "Msemen expired basket", 2, 450);
        createOffer(ownerToken, "Msemen draft basket", 2, 400, true);
        jdbc.sql("UPDATE offer.offers SET quantity_reserved=1, reservation_started_at=now() WHERE id=:id")
                .param("id", soldOut).update();
        jdbc.sql("UPDATE offer.offers SET pickup_start=now() - interval '3 hours', pickup_end=now() - interval '2 hours' WHERE id=:id")
                .param("id", expired).update();
        refreshOfferSearch(soldOut);
        refreshOfferSearch(expired);

        var response = mvc.perform(get("/api/v1/offers/suggestions")
                        .param("q", "mse")
                        .param("city", "Casablanca")
                        .param("district", "Maarif"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(available.toString(), "Msemen breakfast basket", "Msemen & Co Bakery", "\"priceMinor\":650")
                .doesNotContain(soldOut.toString(), expired.toString(), "Msemen draft basket");
        assertThat(response.getContentAsString()).doesNotContain("ownerUserId", "phone", "decisionReason");
    }

    @Test
    void publicSuggestionsRequireAtLeastTwoCharactersAndCapRequestedResults() throws Exception {
        var tooShort = mvc.perform(get("/api/v1/offers/suggestions").param("q", "m"))
                .andReturn().getResponse();
        var capped = mvc.perform(get("/api/v1/offers/suggestions").param("q", "bread").param("limit", "100"))
                .andReturn().getResponse();

        assertThat(tooShort.getStatus()).isEqualTo(400);
        assertThat(capped.getStatus()).isEqualTo(200);
    }

    private void refreshOfferSearch(UUID offerId) {
        events.publishEvent(new OfferEventEnvelope(UUID.randomUUID(), "OfferChanged", 1, Instant.now(),
                offerId, null, Map.of("offerId", offerId)));
    }

    private String approvedMerchantWithCoordinates(String email, String name, String district,
                                                   double latitude, double longitude) throws Exception {
        String id = applyAs(email, name);
        jdbc.sql("UPDATE merchant.merchants SET district=:district, pickup_instructions=:instructions, latitude=:latitude, longitude=:longitude WHERE id=:id")
                .param("district", district).param("instructions", "Pickup at the side counter")
                .param("latitude", latitude).param("longitude", longitude)
                .param("id", UUID.fromString(id)).update();
        var admin = createUser(email + ".admin@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(id));
        return id;
    }

    private String approvedMerchant(String email, String name, String district) throws Exception {
        String id = applyAs(email, name);
        jdbc.sql("UPDATE merchant.merchants SET district=:district, pickup_instructions=:instructions WHERE id=:id")
                .param("district", district).param("instructions", "Pickup at the side counter")
                .param("id", UUID.fromString(id)).update();
        var admin = createUser(email + ".admin@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(id));
        return id;
    }

    private UUID createPublishedOffer(String ownerToken, String title, int quantity, int price) throws Exception {
        UUID id = createOffer(ownerToken, title, quantity, price, true);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/merchants/me/offers/" + id + "/publish").header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        return id;
    }

    private UUID createOffer(String ownerToken, String title, int quantity, int price, boolean pickup) throws Exception {
        String start = pickup ? json(Instant.now().plusSeconds(3600).toString()) : "null";
        String end = pickup ? json(Instant.now().plusSeconds(7200).toString()) : "null";
        String body = """
                {"title":"%s","description":"Surplus food basket","category":"BREAD_PASTRY",
                "priceMinor":%d,"originalPriceMinor":null,"quantityTotal":%d,
                "pickupStart":%s,"pickupEnd":%s,"allergensStatus":"UNKNOWN","allergens":[],
                "foodNotes":null}
                """.formatted(title, price, quantity, start, end);
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/merchants/me/offers").header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        return UUID.fromString(field(response, "id"));
    }

    private static String json(String value) { return "\"" + value + "\""; }
}
