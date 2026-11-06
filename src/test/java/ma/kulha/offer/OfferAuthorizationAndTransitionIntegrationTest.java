package ma.kulha.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.offer.application.OfferApplicationService;
import ma.kulha.offer.application.OfferApi;
import ma.kulha.offer.application.OfferNotFoundException;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.domain.model.StaleOfferVersionException;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OfferAuthorizationAndTransitionIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    MerchantService merchantService;
    @Autowired
    OfferApplicationService offerService;
    @Autowired
    OfferApi offerApi;

    @Test
    void anotherMerchantCannotReadEditOrPublishAnOffer() throws Exception {
        var first = approvedOwner("tenant-a@example.com", "Tenant A Bakery");
        var second = approvedOwner("tenant-b@example.com", "Tenant B Bakery");
        Offer offer = createDraft(first.id(), "Tenant A offer");
        String secondToken = tokenFor(second);

        assertThatThrownBy(() -> offerService.get(second.id(), offer.id())).isInstanceOf(OfferNotFoundException.class);
        assertThatThrownBy(() -> offerService.edit(second.id(), offer.id(), offer.version(), offer.title(),
                offer.description(), offer.category(), offer.priceMinor(), offer.originalPriceMinor(),
                offer.quantityTotal(), offer.pickupStart(), offer.pickupEnd(), offer.allergensStatus(),
                offer.allergens(), offer.foodNotes())).isInstanceOf(OfferNotFoundException.class);
        assertThat(doGet("/api/v1/merchants/me/offers/" + offer.id(), secondToken).getStatus()).isEqualTo(404);
        var edit = mvc.perform(put("/api/v1/merchants/me/offers/" + offer.id())
                .header("Authorization", "Bearer " + secondToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"Stolen edit","description":"Surplus food","category":"OTHER",
                         "priceMinor":500,"originalPriceMinor":null,"quantityTotal":3,
                         "pickupStart":"%s","pickupEnd":"%s","allergensStatus":"UNKNOWN","allergens":[],
                         "foodNotes":null,"version":0}
                        """.formatted(offer.pickupStart(), offer.pickupEnd())))
                .andReturn().getResponse();
        assertThat(edit.getStatus()).isEqualTo(404);
        assertThat(doPostEmpty("/api/v1/merchants/me/offers/" + offer.id() + "/publish", secondToken).getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT title FROM offer.offers WHERE id=:id").param("id", offer.id())
                .query(String.class).single()).isEqualTo(offer.title());
        assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", offer.id())
                .query(String.class).single()).isEqualTo("DRAFT");
    }

    @Test
    void staleVersionAllowsOnlyOneConcurrentEdit() throws Exception {
        var owner = approvedOwner("version-owner@example.com", "Version Bakery");
        Offer offer = createDraft(owner.id(), "Versioned offer");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();
        try {
            Future<?> first = pool.submit(() -> editAtVersion(owner.id(), offer, "First edit", start, success, stale));
            Future<?> second = pool.submit(() -> editAtVersion(owner.id(), offer, "Second edit", start, success, stale));
            start.countDown();
            first.get();
            second.get();
            assertThat(success.get()).isEqualTo(1);
            assertThat(stale.get()).isEqualTo(1);
            String token = tokenFor(owner);
            var staleHttp = mvc.perform(put("/api/v1/merchants/me/offers/" + offer.id())
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"title":"Stale edit","description":"Surplus food","category":"OTHER",
                             "priceMinor":500,"originalPriceMinor":null,"quantityTotal":3,
                             "pickupStart":null,"pickupEnd":null,"allergensStatus":"UNKNOWN","allergens":[],
                             "foodNotes":null,"version":0}
                            """)).andReturn().getResponse();
            assertThat(staleHttp.getStatus()).isEqualTo(409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void merchantSuspensionSuspendsPublishedOffersAndApprovalDoesNotRepublishThem() throws Exception {
        String merchantId = applyAs("suspend-owner@example.com", "Suspension Bakery");
        var admin = createUser("suspend-admin@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        var owner = users.findByEmail("suspend-owner@example.com").orElseThrow();
        Offer draft = createDraft(owner.id(), "Suspension offer");
        Offer published = offerService.publish(owner.id(), draft.id());
        assertThat(published.status().name()).isEqualTo("PUBLISHED");

        assertThat(offerService.get(owner.id(), draft.id()).status().name()).isEqualTo("PUBLISHED");
        assertThat(offerApi.isReservable(draft.id(), Instant.now())).isTrue();
        merchantService.suspend(admin.id(), UUID.fromString(merchantId), "Food safety review");
        assertThat(offerApi.isReservable(draft.id(), Instant.now())).isFalse();
        String ownerToken = tokenFor(owner);
        assertThat(doPostEmpty("/api/v1/merchants/me/offers/" + draft.id() + "/publish", ownerToken).getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", draft.id())
                .query(String.class).single()).isEqualTo("SUSPENDED");
        assertThat(jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type='OFFER' AND target_id=:id AND action='OFFER_SUSPENDED'")
                .param("id", draft.id().toString()).query(Long.class).single()).isEqualTo(1);

        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", draft.id())
                .query(String.class).single()).isEqualTo("SUSPENDED");
    }


    @Test
    void merchantSuspensionSerializesWithAConcurrentStockHold() throws Exception {
        String merchantId = applyAs("suspension-race-owner@example.com", "Suspension Race Bakery");
        var admin = createUser("suspension-race-admin@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        var owner = users.findByEmail("suspension-race-owner@example.com").orElseThrow();
        Offer draft = offerService.create(owner.id(), "Race offer", "Surplus food", OfferCategory.OTHER,
                500, null, 2, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                AllergensStatus.UNKNOWN, List.of(), null);
        offerService.publish(owner.id(), draft.id());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> hold = pool.submit(() -> {
                start.await();
                return offerApi.tryHoldUnits(draft.id(), 1);
            });
            Future<?> suspend = pool.submit(() -> {
                start.await();
                merchantService.suspend(admin.id(), UUID.fromString(merchantId), "Concurrent safety review");
                return null;
            });
            start.countDown();
            hold.get();
            suspend.get();
            assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", draft.id())
                    .query(String.class).single()).isEqualTo("SUSPENDED");
            assertThat(jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type='OFFER' AND target_id=:id AND action='OFFER_SUSPENDED'")
                    .param("id", draft.id().toString()).query(Long.class).single()).isEqualTo(1);
            assertThat(offerApi.isReservable(draft.id(), Instant.now())).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingPickupWindowReturns422WithEveryFieldErrorAndCorrelationIdIsAudited() throws Exception {
        var owner = approvedOwner("field-owner@example.com", "Field Bakery");
        Offer draft = offerService.create(owner.id(), "Field offer", "Surplus food", OfferCategory.OTHER,
                500, null, 3, null, null, AllergensStatus.UNKNOWN, List.of(), null);
        String token = tokenFor(owner);

        var invalid = mvc.perform(post("/api/v1/merchants/me/offers/" + draft.id() + "/publish")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(invalid.getStatus()).isEqualTo(422);
        assertThat(invalid.getContentAsString()).contains("fieldErrors", "pickupStart", "pickupEnd");

        Offer timedDraft = createDraft(owner.id(), "Correlation offer");
        var published = mvc.perform(post("/api/v1/merchants/me/offers/" + timedDraft.id() + "/publish")
                .header("Authorization", "Bearer " + token)
                .header("X-Correlation-Id", "offer-test-correlation-42")).andReturn().getResponse();
        assertThat(published.getStatus()).isEqualTo(200);

        assertThat(published.getHeader("X-Correlation-Id"))
                .isEqualTo("offer-test-correlation-42");

        assertThat(jdbc.sql("""
        SELECT correlation_id
        FROM admin.audit_log
        WHERE target_type = 'OFFER'
          AND target_id = :id
          AND action = 'OFFER_PUBLISHED'
        """)
                .param("id", timedDraft.id().toString())
                .query(String.class)
                .single())
                .isEqualTo("offer-test-correlation-42");
    }

    @Test
    void rejectedMerchantCannotCreateOrPublishOffers() throws Exception {
        String merchantId = applyAs("rejected-owner@example.com", "Rejected Bakery");
        var admin = createUser("rejected-admin@example.com", true, Role.ADMIN);
        merchantService.reject(admin.id(), UUID.fromString(merchantId), "Incomplete documents");
        String ownerToken = tokenForEmail("rejected-owner@example.com");

        var create = mvc.perform(post("/api/v1/merchants/me/offers")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"Rejected offer","description":"Surplus food","category":"OTHER",
                         "priceMinor":500,"originalPriceMinor":null,"quantityTotal":2,
                         "pickupStart":null,"pickupEnd":null,"allergensStatus":"UNKNOWN","allergens":[],"foodNotes":null}
                        """))
                .andReturn().getResponse();
        assertThat(create.getStatus()).isEqualTo(403);
        UUID seededOffer = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
                    quantity_total, pickup_start, pickup_end, allergens_status, allergens)
                VALUES (:id, :merchant, 'Rejected draft', 'Surplus food', 'OTHER', 500,
                    2, now() + interval '1 hour', now() + interval '2 hours', 'UNKNOWN', '{}')
                """).param("id", seededOffer).param("merchant", UUID.fromString(merchantId)).update();
        assertThat(doPostEmpty("/api/v1/merchants/me/offers/" + seededOffer + "/publish", ownerToken).getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", seededOffer)
                .query(String.class).single()).isEqualTo("DRAFT");
    }

    @Test
    void pendingMerchantCannotPublishAnExistingDraft() throws Exception {
        String merchantId = applyAs("pending-publish-owner@example.com", "Pending Publish Bakery");
        var owner = users.findByEmail("pending-publish-owner@example.com").orElseThrow();
        UUID offerId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
                    quantity_total, quantity_reserved, allergens_status, allergens, status, version, created_at, updated_at)
                VALUES (:id, :merchant, 'Pending draft', 'Surplus food', 'OTHER', 500,
                    2, 0, 'UNKNOWN', '{}', 'DRAFT', 0, now(), now())
                """).param("id", offerId).param("merchant", UUID.fromString(merchantId)).update();
        assertThat(doPostEmpty("/api/v1/merchants/me/offers/" + offerId + "/publish", tokenFor(owner)).getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", offerId)
                .query(String.class).single()).isEqualTo("DRAFT");
    }

    @Test
    void customerAndAnonymousCallersCannotUseMerchantOfferRoutes() throws Exception {
        var customer = createUser("offer-customer@example.com", true);
        String customerToken = tokenFor(customer);
        assertThat(doGet("/api/v1/merchants/me/offers", customerToken).getStatus()).isEqualTo(403);
        assertThat(doGet("/api/v1/merchants/me/offers", null).getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/merchants/me/offers")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    private void editAtVersion(UUID ownerId, Offer offer, String title, CountDownLatch start,
                               AtomicInteger success, AtomicInteger stale) {
        try {
            start.await();
            offerService.edit(ownerId, offer.id(), offer.version(), title, offer.description(), offer.category(),
                    offer.priceMinor(), offer.originalPriceMinor(), offer.quantityTotal(), offer.pickupStart(),
                    offer.pickupEnd(), offer.allergensStatus(), offer.allergens(), offer.foodNotes());
            success.incrementAndGet();
        } catch (StaleOfferVersionException conflict) {
            stale.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private ma.kulha.identity.domain.model.User approvedOwner(String email, String name) throws Exception {
        String merchantId = applyAs(email, name);
        var admin = createUser("admin-" + UUID.randomUUID() + "@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        return users.findByEmail(email).orElseThrow();
    }

    private Offer createDraft(UUID ownerId, String title) {
        return offerService.create(ownerId, title, "Surplus food", OfferCategory.OTHER, 500,
                null, 3, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                AllergensStatus.UNKNOWN, List.of(), null);
    }
}
