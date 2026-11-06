package ma.kulha.offer.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfferTest {
    private final Instant now = Instant.parse("2026-11-05T12:00:00Z");
    private Offer draft() {
        return Offer.draft(UUID.randomUUID(), "Bakery basket", "Mixed pastries", OfferCategory.BREAD_PASTRY,
                2500, 3000, 10, now.plusSeconds(3600), now.plusSeconds(7200),
                AllergensStatus.UNKNOWN, List.of(), null, now);
    }
    @Test void publishMovesDraftToPublished() {
        assertThat(draft().publish(now).status()).isEqualTo(OfferStatus.PUBLISHED);
    }
    @Test void publishRejectsPastPickupStart() {
        Offer past = Offer.draft(UUID.randomUUID(), "Bakery basket", "Mixed pastries", OfferCategory.BREAD_PASTRY,
                2500, null, 10, now.minusSeconds(10), now.plusSeconds(600), AllergensStatus.UNKNOWN, List.of(), null, now);
        assertThatThrownBy(() -> past.publish(now)).isInstanceOf(InvalidOfferWindowException.class);
    }
    @Test void publishIsIdempotent() {
        Offer published = draft().publish(now);
        assertThat(published.publish(now.plusSeconds(1))).isSameAs(published);
    }
    @Test void quantityCannotDropBelowReservedUnits() {
        Offer offer = new Offer(UUID.randomUUID(), UUID.randomUUID(), "Title", "Description", OfferCategory.OTHER,
                100, null, 5, 3, now.plusSeconds(60), now.plusSeconds(600), AllergensStatus.UNKNOWN,
                List.of(), null, OfferStatus.DRAFT, null, 0, now, now);
        assertThatThrownBy(() -> offer.changeQuantity(2, 0, now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void reservedOfferLocksPriceAndPickupWindow() {
        Offer offer = new Offer(UUID.randomUUID(), UUID.randomUUID(), "Title", "Description", OfferCategory.OTHER,
                100, null, 5, 1, now.plusSeconds(60), now.plusSeconds(600), AllergensStatus.UNKNOWN,
                List.of(), null, OfferStatus.DRAFT, null, 0, now, now);
        assertThatThrownBy(() -> offer.edit("Title", "Description", OfferCategory.OTHER, 200, null, 5,
                now.plusSeconds(60), now.plusSeconds(600), AllergensStatus.UNKNOWN, List.of(), null, 0, now))
                .isInstanceOf(LockedOfferFieldsException.class);
    }
    @Test void reservabilityUsesTheConfiguredCutoffAndRejectsTheExactBoundary() {
        Offer published = draft().publish(now);
        Instant beforeCutoff = published.pickupEnd().minusSeconds(1801);
        Instant exactCutoff = published.pickupEnd().minusSeconds(1800);
        assertThat(published.isReservable(beforeCutoff, 30)).isTrue();
        assertThat(published.isReservable(exactCutoff, 30)).isFalse();
        assertThat(published.isReservable(exactCutoff, 0)).isTrue();
    }

    @Test void publishRejectsPickupWindowsLongerThanTwentyFourHours() {
        Offer longWindow = Offer.draft(UUID.randomUUID(), "Basket", "Food", OfferCategory.OTHER,
                500, null, 2, now.plus(Duration.ofDays(5)), now.plus(Duration.ofDays(6)).plusSeconds(1),
                AllergensStatus.UNKNOWN, List.of(), null, now);
        assertThatThrownBy(() -> longWindow.publish(now))
                .isInstanceOf(InvalidOfferWindowException.class)
                .satisfies(error -> assertThat(((InvalidOfferWindowException) error).fieldErrors())
                        .containsKey("pickupEnd"));
    }

    @Test void publishRejectsPickupEndMoreThanSevenDaysAhead() {
        Offer tooFar = Offer.draft(UUID.randomUUID(), "Basket", "Food", OfferCategory.OTHER,
                500, null, 2, now.plus(Duration.ofDays(6)).plusSeconds(23 * 3600),
                now.plus(Duration.ofDays(7)).plusSeconds(3601), AllergensStatus.UNKNOWN, List.of(), null, now);
        assertThatThrownBy(() -> tooFar.publish(now))
                .isInstanceOf(InvalidOfferWindowException.class)
                .satisfies(error -> assertThat(((InvalidOfferWindowException) error).fieldErrors())
                        .containsKey("pickupEnd"));
    }

    @Test void publishReportsEveryMissingPickupField() {
        Offer noWindow = Offer.draft(UUID.randomUUID(), "Basket", "Food", OfferCategory.OTHER,
                500, null, 2, null, null, AllergensStatus.UNKNOWN, List.of(), null, now);
        assertThatThrownBy(() -> noWindow.publish(now))
                .isInstanceOf(InvalidOfferWindowException.class)
                .satisfies(error -> assertThat(((InvalidOfferWindowException) error).fieldErrors().keySet())
                        .containsExactlyInAnyOrder("pickupStart", "pickupEnd"));
    }

    @Test void unpublishAndRepublishFollowTheAllowedStateMachine() {
        Offer published = draft().publish(now);
        Offer unpublished = published.unpublish(now.plusSeconds(1));
        assertThat(unpublished.status()).isEqualTo(OfferStatus.UNPUBLISHED);
        assertThat(unpublished.publish(now.plusSeconds(2)).status()).isEqualTo(OfferStatus.PUBLISHED);
        assertThatThrownBy(() -> draft().unpublish(now)).isInstanceOf(InvalidOfferStateException.class);
    }

    @Test void cancelIsAllowedOnlyForDraftOffers() {
        assertThat(draft().cancel(now).status()).isEqualTo(OfferStatus.CANCELLED);
        assertThatThrownBy(() -> draft().publish(now).cancel(now.plusSeconds(1)))
                .isInstanceOf(InvalidOfferStateException.class);
    }

    @Test void suspensionAndReinstatementReturnToUnpublished() {
        Offer published = draft().publish(now);
        assertThat(published.suspend(now.plusSeconds(1)).status()).isEqualTo(OfferStatus.SUSPENDED);
        Offer unpublished = published.unpublish(now.plusSeconds(1));
        assertThat(unpublished.suspend(now.plusSeconds(2)).reinstate(now.plusSeconds(3)).status())
                .isEqualTo(OfferStatus.UNPUBLISHED);
        assertThatThrownBy(() -> draft().suspend(now)).isInstanceOf(InvalidOfferStateException.class);
        assertThatThrownBy(() -> published.reinstate(now)).isInstanceOf(InvalidOfferStateException.class);
    }

    @Test void allergensAndPriceInvariantsAreEnforced() {
        assertThatThrownBy(() -> new Offer(UUID.randomUUID(), UUID.randomUUID(), "Basket", "Food", OfferCategory.OTHER,
                99, null, 2, 0, null, null, AllergensStatus.UNKNOWN, List.of(), null,
                OfferStatus.DRAFT, null, 0, now, now)).isInstanceOf(InvalidOfferDataException.class);
        assertThatThrownBy(() -> new Offer(UUID.randomUUID(), UUID.randomUUID(), "Basket", "Food", OfferCategory.OTHER,
                500, 400, 2, 0, null, null, AllergensStatus.UNKNOWN, List.of(), null,
                OfferStatus.DRAFT, null, 0, now, now)).isInstanceOf(InvalidOfferDataException.class);
        assertThatThrownBy(() -> new Offer(UUID.randomUUID(), UUID.randomUUID(), "Basket", "Food", OfferCategory.OTHER,
                500, null, 2, 0, null, null, AllergensStatus.UNKNOWN, List.of("MILK"), null,
                OfferStatus.DRAFT, null, 0, now, now)).isInstanceOf(InvalidOfferDataException.class);
        assertThatThrownBy(() -> new Offer(UUID.randomUUID(), UUID.randomUUID(), "Basket", "Food", OfferCategory.OTHER,
                500, null, 2, 0, null, null, AllergensStatus.DECLARED, List.of("UNKNOWN_CODE"), null,
                OfferStatus.DRAFT, null, 0, now, now)).isInstanceOf(InvalidOfferDataException.class);
    }

    @Test void duplicateClearsPickupWindowAndCreatesDraft() {
        Offer copy = draft().duplicate(now.plusSeconds(5));
        assertThat(copy.status()).isEqualTo(OfferStatus.DRAFT);
        assertThat(copy.pickupStart()).isNull();
        assertThat(copy.quantityReserved()).isZero();
    }
}
