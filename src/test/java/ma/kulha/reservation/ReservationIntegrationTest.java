package ma.kulha.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ReservationIntegrationTest extends AbstractApiIntegrationTest {

    @Test
    void createsAndConfirmsReservationUsingServerPriceAndOfferSnapshots() throws Exception {
        User customer = createUser("reservation-create@example.test", true);
        Fixture fixture = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        MockHttpServletResponse response = create(tokenFor(customer), fixture, 2);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).contains("\"status\":\"CONFIRMED\"")
                .contains("\"offerTitle\":\"Test basket\"")
                .contains("\"pickupQrPayload\":\"KULHA1:")
                .contains("\"totalPriceMinor\":8000")
                .contains("\"merchantName\":\"Test shop\"")
                .contains("\"pickupCode\"");
        assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                .param("id", fixture.offerId()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void rejectsQuantityAboveLiveInventoryAndPerReservationLimit() throws Exception {
        User customer = createUser("reservation-quantity@example.test", true);
        Fixture fixture = fixture(2, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));

        MockHttpServletResponse response = create(tokenFor(customer), fixture, 3);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("RESERVATION_CONFLICT");
        assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                .param("id", fixture.offerId()).query(Integer.class).single()).isZero();
    }

    @Test
    void rejectsPickupWindowThatHasAlreadyStartedOrExpired() throws Exception {
        User customer = createUser("reservation-expired@example.test", true);
        Fixture fixture = fixture(5, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

        MockHttpServletResponse response = create(tokenFor(customer), fixture, 1);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("RESERVATION_UNAVAILABLE");
    }

    @Test
    void doesNotAllowAnotherCustomerToReadReservationByReference() throws Exception {
        User owner = createUser("reservation-owner@example.test", true);
        User other = createUser("reservation-other@example.test", true);
        Fixture fixture = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        MockHttpServletResponse created = create(tokenFor(owner), fixture, 1);
        String reference = field(created, "reference");

        MockHttpServletResponse response = doGet("/api/v1/reservations/" + reference, tokenFor(other));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void listFilteringSeparatesUpcomingAndPastReservations() throws Exception {
        User customer = createUser("reservation-list@example.test", true);
        Fixture past = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        String token = tokenFor(customer);
        MockHttpServletResponse first = create(token, past, 1);
        String reference = field(first, "reference");
        jdbc.sql("UPDATE reservation.reservations SET status='EXPIRED' WHERE reference=:ref")
                .param("ref", reference).update();

        Fixture completedFixture = fixture(5, Instant.now().plusSeconds(7200), Instant.now().plusSeconds(10800));
        MockHttpServletResponse completed = create(token, completedFixture, 1);
        String completedReference = field(completed, "reference");
        jdbc.sql("UPDATE reservation.reservations SET status='COMPLETED' WHERE reference=:ref")
                .param("ref", completedReference).update();

        Fixture upcoming = fixture(5, Instant.now().plusSeconds(10800), Instant.now().plusSeconds(14400));
        MockHttpServletResponse second = create(token, upcoming, 1);
        assertThat(second.getStatus()).isEqualTo(201);

        MockHttpServletResponse upcomingList = doGet("/api/v1/reservations?status=upcoming", token);
        MockHttpServletResponse pastList = doGet("/api/v1/reservations?status=past", token);
        MockHttpServletResponse cancelledList = doGet("/api/v1/reservations?status=cancelled", token);

        assertThat(upcomingList.getStatus()).isEqualTo(200);
        assertThat(upcomingList.getContentAsString()).contains(field(second, "reference"))
                .doesNotContain(reference).doesNotContain(completedReference);
        assertThat(pastList.getStatus()).isEqualTo(200);
        assertThat(pastList.getContentAsString()).contains(completedReference)
                .doesNotContain(reference).doesNotContain(field(second, "reference"));
        assertThat(cancelledList.getStatus()).isEqualTo(200);
        assertThat(cancelledList.getContentAsString()).contains(reference)
                .doesNotContain(completedReference).doesNotContain(field(second, "reference"));
    }

    @Test
    void cancellationBeforePickupReleasesTheHeldQuantity() throws Exception {
        User customer = createUser("reservation-cancel@example.test", true);
        Fixture fixture = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        String token = tokenFor(customer);
        MockHttpServletResponse created = create(token, fixture, 2);
        String reference = field(created, "reference");
        MockHttpServletResponse response = mvc.perform(patch("/api/v1/reservations/" + reference + "/cancel")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"status\":\"CANCELLED\"");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.cancellation_events WHERE reservation_id=(SELECT id FROM reservation.reservations WHERE reference=:ref)")
                .param("ref", reference).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                .param("id", fixture.offerId()).query(Integer.class).single()).isZero();
    }


    @Test
    void merchantCanCollectWithSingleUseQrCredentialAndCredentialCannotBeReplayed() throws Exception {
        User customer = createUser("reservation-qr-customer@example.test", true);
        Fixture fixture = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        MockHttpServletResponse created = create(tokenFor(customer), fixture, 1);
        String reference = field(created, "reference");
        String qrPayload = field(created, "pickupQrPayload");
        jdbc.sql("UPDATE reservation.reservations SET pickup_start=:start, pickup_end=:end WHERE reference=:ref")
                .param("start", db(Instant.now().minusSeconds(60))).param("end", db(Instant.now().plusSeconds(3600)))
                .param("ref", reference).update();

        String merchantToken = tokenFor(fixture.merchantOwner());
        String body = "{\"qrPayload\":\"%s\"}".formatted(qrPayload);
        MockHttpServletResponse collected = doPost("/api/v1/reservations/collect", body, merchantToken);
        assertThat(collected.getStatus()).isEqualTo(200);
        assertThat(collected.getContentAsString()).contains("\"status\":\"COMPLETED\"");

        MockHttpServletResponse replay = doPost("/api/v1/reservations/collect", body, merchantToken);
        assertThat(replay.getStatus()).isEqualTo(404);
    }

    @Test
    void cancellationCapturesTheStructuredCustomerReason() throws Exception {
        User customer = createUser("reservation-reason@example.test", true);
        Fixture fixture = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        String token = tokenFor(customer);
        MockHttpServletResponse created = create(token, fixture, 1);
        String reference = field(created, "reference");
        MockHttpServletResponse response = mvc.perform(patch("/api/v1/reservations/" + reference + "/cancel")
                .header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"TOO_FAR\",\"comment\":\"Travel plans changed\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT reason_code FROM reservation.cancellation_events WHERE reference=:ref")
                .param("ref", reference).query(String.class).single()).isEqualTo("TOO_FAR");
        assertThat(jdbc.sql("SELECT reason_comment FROM reservation.cancellation_events WHERE reference=:ref")
                .param("ref", reference).query(String.class).single()).isEqualTo("Travel plans changed");
    }

    @Test
    void concurrentCustomersCannotOversellTheLastAvailableUnit() throws Exception {
        User firstCustomer = createUser("reservation-race-a@example.test", true);
        User secondCustomer = createUser("reservation-race-b@example.test", true);
        Fixture fixture = fixture(1, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        String firstToken = tokenFor(firstCustomer);
        String secondToken = tokenFor(secondCustomer);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MockHttpServletResponse> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race start timed out.");
                return create(firstToken, fixture, 1);
            });
            Future<MockHttpServletResponse> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race start timed out.");
                return create(secondToken, fixture, 1);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS).getStatus(),
                    second.get(10, TimeUnit.SECONDS).getStatus());

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                    .param("id", fixture.offerId()).query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM reservation.reservations WHERE offer_id=:id")
                    .param("id", fixture.offerId()).query(Long.class).single()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void replaysTheExactReservationResponseForTheSameCustomerAndIdempotencyKey() throws Exception {
        User customer = createUser("reservation-idempotency-replay@example.test", true);
        Fixture fixture = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        String token = tokenFor(customer);
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first = create(token, fixture, 2, key);
        MockHttpServletResponse replay = create(token, fixture, 2, key);

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.reservations WHERE customer_id=:customer")
                .param("customer", customer.id()).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                .param("id", fixture.offerId()).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.idempotency_keys WHERE customer_id=:customer AND idempotency_key=:key")
                .param("customer", customer.id()).param("key", key).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void rejectsReusingReservationIdempotencyKeyWithDifferentRequestFingerprint() throws Exception {
        User customer = createUser("reservation-idempotency-conflict@example.test", true);
        Fixture fixture = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        String token = tokenFor(customer);
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first = create(token, fixture, 1, key);
        MockHttpServletResponse conflict = create(token, fixture, 2, key);

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(conflict.getStatus()).isEqualTo(409);
        assertThat(conflict.getContentAsString()).contains("RESERVATION_CONFLICT");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.reservations WHERE customer_id=:customer")
                .param("customer", customer.id()).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                .param("id", fixture.offerId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithSameReservationKeyCreateOnlyOneReservation() throws Exception {
        User customer = createUser("reservation-idempotency-race@example.test", true);
        Fixture fixture = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        String token = tokenFor(customer);
        String key = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MockHttpServletResponse> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race start timed out.");
                return create(token, fixture, 2, key);
            });
            Future<MockHttpServletResponse> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race start timed out.");
                return create(token, fixture, 2, key);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MockHttpServletResponse firstResponse = first.get(10, TimeUnit.SECONDS);
            MockHttpServletResponse secondResponse = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResponse.getStatus()).isEqualTo(201);
            assertThat(secondResponse.getStatus()).isEqualTo(201);
            assertThat(firstResponse.getContentAsString()).isEqualTo(secondResponse.getContentAsString());
            assertThat(jdbc.sql("SELECT count(*) FROM reservation.reservations WHERE customer_id=:customer")
                    .param("customer", customer.id()).query(Long.class).single()).isEqualTo(1L);
            assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                    .param("id", fixture.offerId()).query(Integer.class).single()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private MockHttpServletResponse create(String token, Fixture fixture, int quantity) throws Exception {
        return create(token, fixture, quantity, UUID.randomUUID().toString());
    }

    private MockHttpServletResponse create(String token, Fixture fixture, int quantity, String idempotencyKey)
            throws Exception {
        String body = """
                {"offerId":"%s","quantity":%d,"pickupStart":"%s","pickupEnd":"%s"}
                """.formatted(fixture.offerId(), quantity, fixture.pickupStart(), fixture.pickupEnd());
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/reservations")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse();
    }

    private Fixture fixture(int quantity, Instant pickupStart, Instant pickupEnd) {
        // PostgreSQL timestamptz persists microseconds. Use the same precision as API-returned slots.
        pickupStart = pickupStart.truncatedTo(ChronoUnit.MICROS);
        pickupEnd = pickupEnd.truncatedTo(ChronoUnit.MICROS);
        User owner = createUser("shop-owner-" + UUID.randomUUID() + "@example.test", true, Role.MERCHANT);
        UUID merchantId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO merchant.merchants
                    (id, owner_user_id, name, business_type, address, city, phone, opening_hours,
                     pickup_instructions, status, created_at, updated_at)
                VALUES (:id, :owner, 'Test shop', 'RESTAURANT', '12 Rue Test', 'Casablanca',
                        '+212600000000', 'Mon-Sat 09:00-20:00', 'Use the side entrance', 'APPROVED', :now, :now)
                """).param("id", merchantId).param("owner", owner.id()).param("now", db(now)).update();
        jdbc.sql("""
                INSERT INTO offer.offers
                    (id, merchant_id, title, description, category, price_minor, original_price_minor,
                     quantity_total, quantity_reserved, reservation_started_at, pickup_start, pickup_end,
                     allergens_status, allergens, food_notes, status, published_at, version, created_at, updated_at)
                VALUES (:id, :merchant, 'Test basket', 'Test basket description', 'PREPARED_MEALS',
                        4000, 9000, :quantity, 0, NULL, :pickupStart, :pickupEnd,
                        'UNKNOWN', '{}', NULL, 'PUBLISHED', :now, 0, :now, :now)
                """).param("id", offerId).param("merchant", merchantId).param("quantity", quantity)
                .param("pickupStart", db(pickupStart)).param("pickupEnd", db(pickupEnd)).param("now", db(now)).update();
        return new Fixture(offerId, pickupStart, pickupEnd, owner);
    }

    private static OffsetDateTime db(Instant value) { return value.atOffset(ZoneOffset.UTC); }

    private record Fixture(UUID offerId, Instant pickupStart, Instant pickupEnd, User merchantOwner) { }
}
