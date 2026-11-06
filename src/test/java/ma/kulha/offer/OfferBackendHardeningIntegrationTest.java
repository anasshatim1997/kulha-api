package ma.kulha.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.offer.application.OfferApplicationService;
import ma.kulha.offer.application.OfferLimitExceededException;
import ma.kulha.offer.application.OfferApi;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

class OfferBackendHardeningIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    MerchantService merchantService;
    @Autowired
    OfferApplicationService offerService;
    @Autowired
    OfferApi offerApi;

    @Test
    void concurrentCreationNeverExceedsFiftyActiveOffersForOneMerchant() throws Exception {
        String merchantId = applyAs("cap-owner@example.com", "Cap Test Bakery");
        var admin = createUser("cap-admin@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        var owner = users.findByEmail("cap-owner@example.com").orElseThrow();

        int requests = 60;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        offerService.create(owner.id(), "Draft " + index, "Surplus food", OfferCategory.OTHER,
                                500, null, 1, null, null, AllergensStatus.UNKNOWN, List.of(), null);
                        return true;
                    } catch (OfferLimitExceededException limit) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long created = 0;
            for (Future<Boolean> future : futures) if (future.get()) created++;
            assertThat(created).isEqualTo(50);
            assertThat(jdbc.sql("SELECT count(*) FROM offer.offers WHERE merchant_id=:merchant AND status IN ('DRAFT','PUBLISHED')")
                    .param("merchant", UUID.fromString(merchantId)).query(Long.class).single()).isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void republishingAnUnpublishedOfferRespectsTheFiftyActiveOfferLimit() throws Exception {
        String merchantId = applyAs("republish-cap-owner@example.com", "Republish Cap Bakery");
        var admin = createUser("republish-cap-admin@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        var owner = users.findByEmail("republish-cap-owner@example.com").orElseThrow();
        UUID merchantUuid = UUID.fromString(merchantId);

        jdbc.sql("""
                INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
                    quantity_total, quantity_reserved, allergens_status, allergens, status, version, created_at, updated_at)
                SELECT gen_random_uuid(), :merchant, 'Draft ' || n, 'Surplus food', 'OTHER', 500,
                    1, 0, 'UNKNOWN', '{}', 'DRAFT', 0, now(), now()
                FROM generate_series(1, 50) AS n
                """).param("merchant", merchantUuid).update();
        UUID unpublishedId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
                    quantity_total, quantity_reserved, pickup_start, pickup_end, allergens_status,
                    allergens, status, version, created_at, updated_at)
                VALUES (:id, :merchant, 'Unpublished offer', 'Surplus food', 'OTHER', 500,
                    1, 0, now() + interval '1 hour', now() + interval '2 hours', 'UNKNOWN',
                    '{}', 'UNPUBLISHED', 0, now(), now())
                """).param("id", unpublishedId).param("merchant", merchantUuid).update();

        assertThatThrownBy(() -> offerService.publish(owner.id(), unpublishedId))
                .isInstanceOf(OfferLimitExceededException.class);
        assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", unpublishedId)
                .query(String.class).single()).isEqualTo("UNPUBLISHED");
        assertThat(jdbc.sql("SELECT count(*) FROM offer.offers WHERE merchant_id=:merchant AND status IN ('DRAFT','PUBLISHED')")
                .param("merchant", merchantUuid).query(Long.class).single()).isEqualTo(50);
    }

    @Test
    void fiftyConcurrentStockHoldsCannotReserveMoreThanTenUnits() throws Exception {
        UUID offerId = insertPublishedOffer(UUID.randomUUID(), 10, Instant.now().plusSeconds(7200));
        int threads = 50;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return offerApi.tryHoldUnits(offerId, 1);
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) if (future.get()) successes++;
            assertThat(successes).isEqualTo(10);
            assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                    .param("id", offerId).query(Integer.class).single()).isEqualTo(10);
            assertThatThrownBy(() -> offerApi.releaseUnits(offerId, 11)).isInstanceOf(IllegalStateException.class);
            offerApi.releaseUnits(offerId, 10);
            assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                    .param("id", offerId).query(Integer.class).single()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void stockHoldHandlesTheExactLastUnitAndDatabaseRejectsReservedAboveTotal() {
        UUID offerId = insertPublishedOffer(UUID.randomUUID(), 1, Instant.now().plusSeconds(7200));
        assertThat(offerApi.tryHoldUnits(offerId, 1)).isTrue();
        assertThat(offerApi.tryHoldUnits(offerId, 1)).isFalse();
        assertThatThrownBy(() -> jdbc.sql("UPDATE offer.offers SET quantity_reserved=2 WHERE id=:id")
                .param("id", offerId).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE offer.offers SET quantity_reserved=-1 WHERE id=:id")
                .param("id", offerId).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE offer.offers SET price_minor=99 WHERE id=:id")
                .param("id", offerId).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id").param("id", offerId)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void priceAndPickupRemainLockedAfterAllReservedUnitsAreReleased() throws Exception {
        String merchantId = applyAs("released-units-owner@example.com", "Released Units Bakery");
        var admin = createUser("released-units-admin@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        var owner = users.findByEmail("released-units-owner@example.com").orElseThrow();
        Offer draft = offerService.create(owner.id(), "Released units basket", "Surplus food", OfferCategory.OTHER,
                500, null, 2, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                AllergensStatus.UNKNOWN, List.of(), null);
        Offer published = offerService.publish(owner.id(), draft.id());
        UUID offerId = published.id();
        assertThat(offerApi.tryHoldUnits(offerId, 1)).isTrue();
        offerApi.releaseUnits(offerId, 1);
        assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id").param("id", offerId)
                .query(Integer.class).single()).isZero();
        var saved = offerService.get(owner.id(), offerId);
        assertThat(saved.reservationStartedAt()).isNotNull();
        assertThatThrownBy(() -> offerService.edit(owner.id(), offerId, saved.version(), saved.title(),
                saved.description(), saved.category(), saved.priceMinor() + 100, saved.originalPriceMinor(),
                saved.quantityTotal(), saved.pickupStart(), saved.pickupEnd(), saved.allergensStatus(),
                saved.allergens(), saved.foodNotes())).isInstanceOf(ma.kulha.offer.domain.model.LockedOfferFieldsException.class);
    }

    @Test
    void mixedHoldAndReleaseStormKeepsStockWithinBounds() throws Exception {
        UUID offerId = insertPublishedOffer(UUID.randomUUID(), 10, Instant.now().plusSeconds(7200));
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < 20; j++) {
                        if (offerApi.tryHoldUnits(offerId, 1)) offerApi.releaseUnits(offerId, 1);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get();
            Integer reserved = jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                    .param("id", offerId).query(Integer.class).single();
            assertThat(reserved).isBetween(0, 10);
            assertThat(reserved).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiryProcessesTwelveHundredRowsInBatchesAndIsIdempotent() {
        UUID merchantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
                    quantity_total, quantity_reserved, pickup_start, pickup_end, allergens_status,
                    allergens, status, published_at, version, created_at, updated_at)
                SELECT gen_random_uuid(), :merchant, 'Expired offer', 'Expired test item', 'OTHER', 500,
                    1, 0, now() - interval '3 hours', now() - interval '2 hours', 'UNKNOWN',
                    '{}', 'PUBLISHED', now() - interval '3 hours', 0, now() - interval '4 hours', now() - interval '4 hours'
                FROM generate_series(1, 1200)
                """).param("merchant", merchantId).update();

        assertThat(offerService.expireDueOffers()).isEqualTo(1200);
        assertThat(jdbc.sql("SELECT count(*) FROM offer.offers WHERE status='EXPIRED'").query(Long.class).single()).isEqualTo(1200);
        assertThat(jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type='OFFER' AND action='OFFER_EXPIRED'")
                .query(Long.class).single()).isEqualTo(1200);
        assertThat(offerService.expireDueOffers()).isZero();
    }

    @Test
    void databaseRejectsAnOfferWithOnlyOnePickupTimestamp() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
                    quantity_total, pickup_start, pickup_end, allergens_status, allergens)
                VALUES (:id, :merchant, 'Invalid window', 'One-sided window', 'OTHER', 500,
                    1, now(), NULL, 'UNKNOWN', '{}')
                """).param("id", UUID.randomUUID()).param("merchant", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRequiresFirstReservationTimestampWhenUnitsAreReserved() {
        UUID offerId = insertPublishedOffer(UUID.randomUUID(), 2, Instant.now().plusSeconds(7200));
        assertThatThrownBy(() -> jdbc.sql("UPDATE offer.offers SET quantity_reserved=1 WHERE id=:id")
                .param("id", offerId).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void failedAuditInsertRollsBackTheOfferTransition() throws Exception {
        String merchantId = applyAs("rollback-owner@example.com", "Rollback Test Bakery");
        var admin = createUser("rollback-admin@example.com", true, Role.ADMIN);
        merchantService.approve(admin.id(), UUID.fromString(merchantId));
        var owner = users.findByEmail("rollback-owner@example.com").orElseThrow();
        Offer draft = offerService.create(owner.id(), "Rollback basket", "Surplus food", OfferCategory.OTHER,
                500, null, 2, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                AllergensStatus.UNKNOWN, List.of(), null);

        jdbc.sql("""
                CREATE OR REPLACE FUNCTION admin.fail_offer_publish_for_test() RETURNS trigger AS $$
                BEGIN
                    IF NEW.target_type = 'OFFER' AND NEW.action = 'OFFER_PUBLISHED' THEN
                        RAISE EXCEPTION 'forced audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("CREATE TRIGGER fail_offer_publish_for_test BEFORE INSERT ON admin.audit_log FOR EACH ROW EXECUTE FUNCTION admin.fail_offer_publish_for_test()").update();
        try {
            assertThatThrownBy(() -> offerService.publish(owner.id(), draft.id())).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.sql("SELECT status FROM offer.offers WHERE id=:id").param("id", draft.id()).query(String.class).single())
                    .isEqualTo("DRAFT");
            assertThat(jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type='OFFER' AND target_id=:id")
                    .param("id", draft.id().toString()).query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS fail_offer_publish_for_test ON admin.audit_log").update();
            jdbc.sql("DROP FUNCTION IF EXISTS admin.fail_offer_publish_for_test()").update();
        }
    }

    private UUID insertPublishedOffer(UUID merchantId, int quantity, Instant pickupEnd) {
        UUID offerId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
                    quantity_total, quantity_reserved, pickup_start, pickup_end, allergens_status,
                    allergens, status, published_at, version, created_at, updated_at)
                VALUES (:id, :merchant, 'Stock test', 'Stock concurrency', 'OTHER', 500,
                    :quantity, 0, :pickupStart, :pickupEnd, 'UNKNOWN', '{}', 'PUBLISHED', now(), 0, now(), now())
                """)
                .param("id", offerId).param("merchant", merchantId).param("quantity", quantity)
                .param("pickupStart", Instant.now().minusSeconds(600).atOffset(java.time.ZoneOffset.UTC))
                .param("pickupEnd", pickupEnd.atOffset(java.time.ZoneOffset.UTC)).update();
        return offerId;
    }
}
