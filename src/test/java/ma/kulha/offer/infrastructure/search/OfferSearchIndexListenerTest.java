package ma.kulha.offer.infrastructure.search;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import ma.kulha.merchant.application.MerchantPublicApi;
import ma.kulha.merchant.application.MerchantPublicProfile;
import ma.kulha.offer.domain.event.OfferExpiryBatchEvent;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.domain.repository.OfferRepository;
import org.junit.jupiter.api.Test;

class OfferSearchIndexListenerTest {
    @Test
    void processesLargeExpiryNotificationsInSearchBatches() {
        OfferRepository offers = mock(OfferRepository.class);
        MerchantPublicApi merchants = mock(MerchantPublicApi.class);
        ElasticsearchOfferSearch search = mock(ElasticsearchOfferSearch.class);
        when(offers.findByIds(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());
        OfferSearchIndexListener listener = new OfferSearchIndexListener(offers, merchants, search,
                Clock.fixed(NOW, ZoneOffset.UTC), 30, true);
        List<UUID> expiredIds = IntStream.range(0, 1200).mapToObj(ignored -> UUID.randomUUID()).toList();

        listener.onOffersExpired(new OfferExpiryBatchEvent(expiredIds));

        verify(search, times(3)).applyBatch(org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.anyList());
        verify(offers, times(3)).findByIds(org.mockito.ArgumentMatchers.anyList());
    }

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    @Test
    void indexesOnlyReservableOffersWithAnApprovedPublicMerchantProfile() {
        OfferRepository offers = mock(OfferRepository.class);
        MerchantPublicApi merchants = mock(MerchantPublicApi.class);
        ElasticsearchOfferSearch search = mock(ElasticsearchOfferSearch.class);
        UUID merchantId = UUID.randomUUID();
        Offer published = Offer.draft(merchantId, "Msemen", "Fresh pastry", OfferCategory.BREAD_PASTRY,
                500, null, 5, NOW.plusSeconds(3600), NOW.plusSeconds(7200), AllergensStatus.UNKNOWN,
                List.of(), null, NOW).publish(NOW);
        MerchantPublicProfile profile = new MerchantPublicProfile(merchantId, "Local bakery", "Address",
                "Casablanca", "Maarif", null);
        when(offers.findByIds(List.of(published.id()))).thenReturn(List.of(published));
        when(merchants.findApprovedPublicProfiles(List.of(merchantId))).thenReturn(List.of(profile));
        OfferSearchIndexListener listener = new OfferSearchIndexListener(offers, merchants, search,
                Clock.fixed(NOW, ZoneOffset.UTC), 30, false);

        listener.defer(published.id());
        listener.processPending();

        verify(search).applyBatch(org.mockito.ArgumentMatchers.argThat(documents -> documents.size() == 1
                        && documents.get(0).id().equals(published.id())
                        && documents.get(0).merchantName().equals("Local bakery")
                        && documents.get(0).availableUnits() == 5), org.mockito.ArgumentMatchers.eq(List.of()));
    }

    @Test
    void removesOffersThatAreNotPublishedOrNoLongerReservable() {
        OfferRepository offers = mock(OfferRepository.class);
        MerchantPublicApi merchants = mock(MerchantPublicApi.class);
        ElasticsearchOfferSearch search = mock(ElasticsearchOfferSearch.class);
        UUID merchantId = UUID.randomUUID();
        Offer draft = Offer.draft(merchantId, "Msemen", "Fresh pastry", OfferCategory.BREAD_PASTRY,
                500, null, 5, NOW.plusSeconds(3600), NOW.plusSeconds(7200), AllergensStatus.UNKNOWN,
                List.of(), null, NOW);
        when(offers.findByIds(List.of(draft.id()))).thenReturn(List.of(draft));
        OfferSearchIndexListener listener = new OfferSearchIndexListener(offers, merchants, search,
                Clock.fixed(NOW, ZoneOffset.UTC), 30, false);

        listener.defer(draft.id());
        listener.processPending();

        verify(search).applyBatch(org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(List.of(draft.id())));
        org.mockito.Mockito.verifyNoInteractions(merchants);
    }
}
