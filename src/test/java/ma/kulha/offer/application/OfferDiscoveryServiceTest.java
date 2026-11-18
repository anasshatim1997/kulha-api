package ma.kulha.offer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import ma.kulha.merchant.application.MerchantPublicApi;
import ma.kulha.merchant.application.MerchantPublicProfile;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.domain.model.PublicOfferRow;
import ma.kulha.offer.domain.repository.OfferRepository;
import ma.kulha.offer.infrastructure.search.ElasticsearchOfferSearch;
import ma.kulha.offer.infrastructure.search.OfferSearchDocument;
import ma.kulha.offer.infrastructure.search.OfferSearchPage;
import org.junit.jupiter.api.Test;

class OfferDiscoveryServiceTest {
    @Test
    void searchUsesElasticsearchForDiscoveryAndHydratesAuthoritativeRowsFromPostgres() {
        OfferRepository offers = mock(OfferRepository.class);
        MerchantPublicApi merchants = mock(MerchantPublicApi.class);
        ElasticsearchOfferSearch search = mock(ElasticsearchOfferSearch.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
        UUID offerId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        PublicOfferRow row = new PublicOfferRow(offerId, merchantId, "Msemen", "Fresh basket",
                OfferCategory.BREAD_PASTRY, 500, 700, 2, 2,
                Instant.parse("2026-11-06T14:00:00Z"), Instant.parse("2026-11-06T16:00:00Z"),
                AllergensStatus.UNKNOWN, List.of(), null);
        MerchantPublicProfile profile = new MerchantPublicProfile(merchantId, "Local shop", "1 Main Street",
                "Casablanca", "Maarif", null);
        when(search.search(null, null, null, null, null, null, null, true, false, "pickup_end", 0, 20,
                null, null, null, Instant.parse("2026-11-06T12:30:00Z")))
                .thenReturn(new OfferSearchPage(List.of(offerId), 1));
        when(offers.findPublicByIds(List.of(offerId), Instant.parse("2026-11-06T12:30:00Z"), true)).thenReturn(List.of(row));
        when(merchants.findApprovedPublicProfiles(List.of(merchantId))).thenReturn(List.of(profile));

        OfferDiscoveryService service = new OfferDiscoveryService(offers, merchants, search, clock, 30);
        var page = service.search(null, null, null, null, null, null, null, true, "pickup_end", 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).merchant().name()).isEqualTo("Local shop");
        assertThat(page.total()).isEqualTo(1);
        verify(search).search(null, null, null, null, null, null, null, true, false, "pickup_end", 0, 20,
                null, null, null, Instant.parse("2026-11-06T12:30:00Z"));
        verify(offers).findPublicByIds(List.of(offerId), Instant.parse("2026-11-06T12:30:00Z"), true);
    }

    @Test
    void emptySearchDoesNotOpenPostgresOrLoadMerchantProfiles() {
        OfferRepository offers = mock(OfferRepository.class);
        MerchantPublicApi merchants = mock(MerchantPublicApi.class);
        ElasticsearchOfferSearch search = mock(ElasticsearchOfferSearch.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
        when(search.search(null, null, null, null, null, null, null, true, false, "pickup_end", 0, 24,
                null, null, null, Instant.parse("2026-11-06T12:30:00Z")))
                .thenReturn(new OfferSearchPage(List.of(), 0));

        OfferDiscoveryService service = new OfferDiscoveryService(offers, merchants, search, clock, 30);
        var page = service.search(null, null, null, null, null, null, null, true, "pickup_end", 0, 24);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        org.mockito.Mockito.verifyNoInteractions(offers, merchants);
    }

    @Test
    void nearbySearchReturnsRoundedDistanceFromTheGeoSort() {
        OfferRepository offers = mock(OfferRepository.class);
        MerchantPublicApi merchants = mock(MerchantPublicApi.class);
        ElasticsearchOfferSearch search = mock(ElasticsearchOfferSearch.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
        UUID offerId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        PublicOfferRow row = new PublicOfferRow(offerId, merchantId, "Msemen", "Fresh basket",
                OfferCategory.BREAD_PASTRY, 500, 700, 2, 2,
                Instant.parse("2026-11-06T14:00:00Z"), Instant.parse("2026-11-06T16:00:00Z"),
                AllergensStatus.UNKNOWN, List.of(), null);
        MerchantPublicProfile profile = new MerchantPublicProfile(merchantId, "Local shop", "1 Main Street",
                "Casablanca", "Maarif", null, 33.589, -7.62);
        when(search.search(null, null, null, null, null, null, null, true, false, "distance", 0, 20,
                33.59, -7.62, 5000, Instant.parse("2026-11-06T12:30:00Z")))
                .thenReturn(new OfferSearchPage(List.of(offerId), 1, java.util.Map.of(offerId, 845.6)));
        when(offers.findPublicByIds(List.of(offerId), Instant.parse("2026-11-06T12:30:00Z"), true)).thenReturn(List.of(row));
        when(merchants.findApprovedPublicProfiles(List.of(merchantId))).thenReturn(List.of(profile));

        OfferDiscoveryService service = new OfferDiscoveryService(offers, merchants, search, clock, 30);
        var page = service.search(null, null, null, null, null, null, null, true, false,
                "distance", 0, 20, 33.59, -7.62, 5000);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).distanceKm()).isEqualTo(0.8);
        verify(search).search(null, null, null, null, null, null, null, true, false, "distance", 0, 20,
                33.59, -7.62, 5000, Instant.parse("2026-11-06T12:30:00Z"));
    }

    @Test
    void suggestionsAreServedByElasticsearchWithoutARepositorySearch() {
        OfferRepository offers = mock(OfferRepository.class);
        MerchantPublicApi merchants = mock(MerchantPublicApi.class);
        ElasticsearchOfferSearch search = mock(ElasticsearchOfferSearch.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
        OfferSearchDocument suggestion = new OfferSearchDocument(UUID.randomUUID(), UUID.randomUUID(), "Msemen",
                "Traditional pastry", "BREAD_PASTRY", 500, null, 4, 3,
                Instant.parse("2026-11-06T14:00:00Z"), Instant.parse("2026-11-06T16:00:00Z"),
                Instant.parse("2026-11-06T11:00:00Z"), Instant.parse("2026-11-06T10:00:00Z"),
                "Local shop", "Casablanca", "Maarif", null, null, List.of(), "UNKNOWN", null);
        when(search.suggest("mse", "Casablanca", "Maarif", 6,
                Instant.parse("2026-11-06T12:30:00Z"))).thenReturn(List.of(suggestion));

        OfferDiscoveryService service = new OfferDiscoveryService(offers, merchants, search, clock, 30);
        var result = service.suggest("mse", "Casablanca", "Maarif", 6);

        assertThat(result).containsExactly(new OfferSuggestion(suggestion.id(), suggestion.title(), suggestion.category(),
                suggestion.priceMinor(), suggestion.merchantName(), suggestion.city(), suggestion.district()));
        verify(search).suggest("mse", "Casablanca", "Maarif", 6, Instant.parse("2026-11-06T12:30:00Z"));
        org.mockito.Mockito.verifyNoInteractions(offers, merchants);
    }
}
