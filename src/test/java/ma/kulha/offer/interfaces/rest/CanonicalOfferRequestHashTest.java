package ma.kulha.offer.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.interfaces.dto.OfferRequest;
import org.junit.jupiter.api.Test;

class CanonicalOfferRequestHashTest {
    @Test
    void semanticallyEquivalentRequestsHaveTheSameHash() {
        Instant start = Instant.parse("2026-12-01T10:00:00Z");
        Instant end = Instant.parse("2026-12-01T11:00:00Z");
        OfferRequest first = new OfferRequest(" Basket ", " Fresh food ", OfferCategory.BREAD_PASTRY,
                500, null, 3, start, end, AllergensStatus.DECLARED, List.of("MILK", "GLUTEN"), " note ", null);
        OfferRequest second = new OfferRequest("Basket", "Fresh food", OfferCategory.BREAD_PASTRY,
                500, null, 3, OffsetDateTime.parse("2026-12-01T11:00:00+01:00").toInstant(), end,
                AllergensStatus.DECLARED, List.of("GLUTEN", "MILK", "MILK"), "note", 42L);
        assertThat(CanonicalOfferRequestHash.create(first)).isEqualTo(CanonicalOfferRequestHash.create(second));
    }

    @Test
    void differentBusinessValuesHaveDifferentHashes() {
        OfferRequest first = new OfferRequest("Basket", "Fresh food", OfferCategory.BREAD_PASTRY,
                500, null, 3, null, null, AllergensStatus.UNKNOWN, List.of(), null, null);
        OfferRequest second = new OfferRequest("Basket", "Fresh food", OfferCategory.BREAD_PASTRY,
                600, null, 3, null, null, AllergensStatus.UNKNOWN, List.of(), null, null);
        assertThat(CanonicalOfferRequestHash.create(first)).isNotEqualTo(CanonicalOfferRequestHash.create(second));
    }
}
