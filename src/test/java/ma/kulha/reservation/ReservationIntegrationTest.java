package ma.kulha.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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
    void cancellationCapturesStructuredReasonAndOptionalCommentAtomically() throws Exception {
        User customer = createUser("reservation-cancel-reason@example.test", true);
        Fixture fixture = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        String token = tokenFor(customer);
        MockHttpServletResponse created = create(token, fixture, 1);
        String reference = field(created, "reference");

        MockHttpServletResponse response = mvc.perform(patch("/api/v1/reservations/" + reference + "/cancel")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reasonCode":"TOO_FAR","comment":"I cannot reach the shop today."}
                        """)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("""
                SELECT reason_code FROM reservation.cancellation_events
                 WHERE reservation_id = (SELECT id FROM reservation.reservations WHERE reference=:reference)
                """).param("reference", reference).query(String.class).single()).isEqualTo("TOO_FAR");
        assertThat(jdbc.sql("""
                SELECT comment FROM reservation.cancellation_events
                 WHERE reservation_id = (SELECT id FROM reservation.reservations WHERE reference=:reference)
                """).param("reference", reference).query(String.class).single())
                .isEqualTo("I cannot reach the shop today.");
    }

    @Test
    void pickupQrCredentialIsOpaqueSingleUseAndBoundToTheOwningMerchant() throws Exception {
        User customer = createUser("reservation-qr-customer@example.test", true);
        User merchantOwner = createUser("reservation-qr-merchant@example.test", true, Role.MERCHANT);
        Fixture fixture = fixture(5, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), merchantOwner);
        String customerToken = tokenFor(customer);
        MockHttpServletResponse created = create(customerToken, fixture, 1);
        String reference = field(created, "reference");
        jdbc.sql("""
                UPDATE reservation.reservations
                   SET pickup_start=:start, pickup_end=:end
                 WHERE reference=:reference
                """).param("start", db(Instant.now().minusSeconds(60)))
                .param("end", db(Instant.now().plusSeconds(1800))).param("reference", reference).update();

        MockHttpServletResponse credential = mvc.perform(post("/api/v1/reservations/" + reference + "/pickup-credential")
                .header("Authorization", "Bearer " + customerToken)).andReturn().getResponse();
        assertThat(credential.getStatus()).isEqualTo(200);
        String qrPayload = field(credential, "qrPayload");
        assertThat(qrPayload).startsWith("KULHA-PICKUP:");
        assertThat(credential.getContentAsString()).contains("data:image/png;base64,");
        assertThat(jdbc.sql("""
                SELECT pickup_credential_hash FROM reservation.reservations WHERE reference=:reference
                """).param("reference", reference).query(String.class).single()).hasSize(64);

        String merchantToken = tokenFor(merchantOwner);
        String collectBody = "{\\"token\\":\\"" + qrPayload + "\\"}";
        MockHttpServletResponse collected = doPost("/api/v1/reservations/collect", collectBody, merchantToken);
        assertThat(collected.getStatus()).isEqualTo(200);
        assertThat(collected.getContentAsString()).contains("\\"status\\":\\"COMPLETED\\"");

        MockHttpServletResponse reused = doPost("/api/v1/reservations/collect", collectBody, merchantToken);
        assertThat(reused.getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("""
                SELECT pickup_credential_hash FROM reservation.reservations WHERE reference=:reference
                """).param("reference", reference).query(String.class).optional()).isEmpty();
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
        assertThat(jdbc.sql("SELECT quantity_reserved FROM offer.offers WHERE id=:id")
                .param("id", fixture.offerId()).query(Integer.class).single()).isZero();
    }

    private MockHttpServletResponse create(String token, Fixture fixture, int quantity) throws Exception {
        String body = """
                {"offerId":"%s","quantity":%d,"pickupStart":"%s","pickupEnd":"%s"}
                """.formatted(fixture.offerId(), quantity, fixture.pickupStart(), fixture.pickupEnd());
        return doPost("/api/v1/reservations", body, token);
    }

    private Fixture fixture(int quantity, Instant pickupStart, Instant pickupEnd) {
        User owner = createUser("shop-owner-" + UUID.randomUUID() + "@example.test", true);
        return fixture(quantity, pickupStart, pickupEnd, owner);
    }

    private Fixture fixture(int quantity, Instant pickupStart, Instant pickupEnd, User owner) {
        // PostgreSQL timestamptz persists microseconds. Use the same precision as API-returned slots.
        pickupStart = pickupStart.truncatedTo(ChronoUnit.MICROS);
        pickupEnd = pickupEnd.truncatedTo(ChronoUnit.MICROS);
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
        return new Fixture(offerId, merchantId, pickupStart, pickupEnd);
    }

    private static OffsetDateTime db(Instant value) { return value.atOffset(ZoneOffset.UTC); }

    private record Fixture(UUID offerId, UUID merchantId, Instant pickupStart, Instant pickupEnd) { }
}
